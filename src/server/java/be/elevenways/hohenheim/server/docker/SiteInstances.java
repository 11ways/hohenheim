package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.build.BuildArtifacts;
import be.elevenways.hohenheim.server.build.BuildQuota;
import be.elevenways.hohenheim.server.build.BuildRequest;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The wiring between the SITE tier and the canonical runtime-resource contract: every
 * Docker site's running release IS an owned {@code hohenheim:site_container} instance,
 * deployed/stopped/destroyed through {@link InstanceService} -- the site tier keeps
 * routing, domains, TLS and revisions, and no longer talks to the daemon about its own
 * container. The instance row is a GENERATED row attributed to the site (the
 * GeneratedRows discipline): written only inside this class's system scope, refused
 * read-only everywhere else, invisible to the standalone instance UI/API.
 *
 * AIDEV-NOTE: entries here run inside {@code GeneratedRows.as(...)} DELIBERATELY. The
 * site tier's own authority gates (resource layer + TenantWrites invariants) already
 * judged the record write that leads here; the runtime convergence that follows is
 * system work, exactly like the ACME publisher. Without the scope, a tenant toggling
 * their (granted) site would be re-asked for INSTANCE capabilities they structurally
 * cannot hold, and the instance-tier tenant gates (image policy, quota owner
 * derivation) would judge a system consequence as a tenant write.
 */
public final class SiteInstances {

    /** The GeneratedRows source token, and the Accountability origin of every write here. */
    public static final String SOURCE = "site";

    /**
     * The handler generation currently responsible for each site's runtime.
     * SiteDispatcher builds a NEW handler generation before destroying the previous one,
     * and both point at the SAME owned instance -- without this guard the outgoing
     * generation's destroy() would stop the workload the incoming generation just
     * deployed (the shared-instance version of the old ledger bug where the outgoing
     * handler released the incoming handler's fresh port claim).
     */
    private static final Map<Integer, Object> ACTIVE_HANDLERS = new ConcurrentHashMap<>();

    private static volatile boolean installed;

    private SiteInstances() {
    }

    /**
     * Install the ownership funnel on the instance write pipeline (MODULES stage): the
     * GeneratedRows attribution guard, plus the refusal that keeps {@code site_container}
     * a site-authored kind -- outside the system scope the kind cannot be written at all,
     * so the admin instance form cannot become a second authority over site runtimes.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        GeneratedRows.installGuards(InstanceModel.SCHEMA, InstanceModel.class,
            new GeneratedRows.Columns(InstanceModel.GENERATED_BY,
                InstanceModel.GENERATED_FOR_MODEL, InstanceModel.GENERATED_FOR_ID,
                InstanceModel.GENERATED_AT),
            "instance_generated_readonly", "instance_generated_attribution");
        InstanceModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || GeneratedRows.inSystemScope()) {
                return;
            }
            if (SiteContainerKind.ID.toString().equals(row.get(InstanceModel.KIND))) {
                throw Violations.ofField(InstanceModel.KIND.getName(),
                    row.get(InstanceModel.KIND),
                    Microcopy.of("site_container_site_managed").withFilter("scope", "violations"));
            }
        });
    }

    /** The outcome of one convergence pass: the owned instance and its live status. */
    public record SiteRuntime(int instanceId, @NonNull InstanceStatus status) {
    }

    /**
     * Converge the site's owned instance onto the resolved settings: create or update
     * the instance row, retire the pre-lowering {@code hohenheim-site-N} container once,
     * build the image for a git-sourced site, and deploy ONLY when the desired settings
     * changed or the workload is not running -- an unchanged, running release is reused,
     * so a routing reload no longer restarts every Docker site.
     *
     * @throws Violations naming the refusal (quota, image, fence, daemon); the caller
     *         (the site handler) reports DOWN, there is no other path to a container
     */
    public static @NonNull SiteRuntime ensureRunning(int siteId, @Nullable String siteName,
                                                     @NonNull Map<String, Object> settings) {
        DockerClient docker = dockerFor(settings);
        int serverId = ServerModel.canonicalServerId(settings.get("server"));
        String siteFingerprint = SiteReleases.sourceFingerprint(siteId, settings);

        try {
            return inScope(siteId, () -> {
                Row serving = ownedServing(siteId);

                // A build context WITHOUT a commit identity has no source fingerprint a
                // reload can trust (the content can change under the same path), so it
                // always resolves the spec; GitDeployment always supplies commit_sha.
                boolean fingerprintable = str(settings.get("build_context")).isEmpty()
                    || !str(settings.get("commit_sha")).isEmpty();

                // Fast lane: unchanged source, running workload -- reuse without even
                // resolving the spec (which for a git-sourced site means a sandbox
                // build). The fingerprint IS the "would a release change anything" test.
                if (fingerprintable && serving != null
                        && InstanceModel.STATUS_RUNNING.equals(serving.get(InstanceModel.STATUS))
                        && siteFingerprint.equals(
                            storedSettings(serving).get("source_fingerprint"))
                        && serverId == ServerModel.canonicalServerId(
                            serving.get(InstanceModel.SERVER_ID))) {
                    int servingId = serving.get(InstanceModel.ID);
                    InstanceStatus live =
                        reusableStatus(docker, servingId, storedSettings(serving));
                    if (live != null) {
                        return new SiteRuntime(servingId, live);
                    }
                }

                // Rollback pin: the operator rejected exactly this source, so converge
                // on the ROLLED-BACK release instead of re-releasing the rejected spec.
                if (serving != null && SiteReleases.pinnedByRollback(siteId, siteFingerprint)) {
                    int servingId = serving.get(InstanceModel.ID);
                    if (InstanceModel.STATUS_RUNNING.equals(serving.get(InstanceModel.STATUS))) {
                        InstanceStatus live =
                            reusableStatus(docker, servingId, storedSettings(serving));
                        if (live != null) {
                            return new SiteRuntime(servingId, live);
                        }
                    }
                    return new SiteRuntime(servingId, new InstanceService().deploy(servingId));
                }

                if (serving == null) {
                    retireLegacyContainer(docker, siteId);
                    Map<String, Object> desired = desiredSettings(docker, siteId, settings);
                    desired.put("source_fingerprint", siteFingerprint);
                    return SiteReleases.initialRelease(siteId, siteName, serverId,
                        desired, siteFingerprint);
                }

                // Changed source over an existing release: the health-gated lane.
                return SiteReleases.release(docker, siteId, siteName, serverId, serving,
                    settings, siteFingerprint);
            });
        } catch (Violations refused) {
            throw refused;
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Live status of the site's SERVING release; DOWN-shaped when none exists. */
    public static @Nullable InstanceStatus liveStatus(int siteId) {
        Row instance = ownedServing(siteId);
        if (instance == null) {
            return null;
        }
        return new InstanceService().liveStatus(instance.get(InstanceModel.ID));
    }

    /**
     * Stop the site's running releases (route drop: disable, settings edit, soft
     * delete) -- the serving one plus any still-draining retired one; the retained
     * rollback target is normally stopped already. Never throws -- the handler
     * teardown path must not; a failed stop leaves the claims parked by
     * InstanceService.stop and the reconciler surfaces the rest.
     */
    public static void stopFor(int siteId) {
        try {
            inScope(siteId, () -> {
                for (Row instance : ownedInstances(siteId)) {
                    if (InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS))
                            || InstanceModel.STATUS_STARTING.equals(
                                instance.get(InstanceModel.STATUS))) {
                        new InstanceService().stop(instance.get(InstanceModel.ID));
                    }
                }
                return null;
            });
        } catch (Exception e) {
            Blast.log("SITE-RUNTIME: stop of site", siteId, "runtime failed -", e.getMessage());
        }
    }

    /**
     * Verified end of life, called explicitly by the site delete path (the
     * GameDomains.deleteForInstance shape: the instance soft-deletes, so nothing else
     * would ever clean it up). EVERY release of the site dies here -- serving,
     * retained rollback target and any in-flight candidate alike: container removed
     * or observed absent, claims released fully, instance rows soft-deleted.
     *
     * @throws Violations when the daemon cannot confirm a teardown -- the site delete
     *         is refused rather than leaving a running container behind a dead record
     */
    public static void destroyFor(int siteId) {
        List<Row> owned = ownedInstances(siteId);
        if (owned.isEmpty()) {
            return;
        }
        try {
            inScope(siteId, () -> {
                for (Row instance : owned) {
                    new InstanceService().destroy(instance.get(InstanceModel.ID));
                }
                return null;
            });
            // The workload is gone, so nothing holds the site's build artifacts any more.
            // Without this they dangle forever: the per-build prune skips whatever a
            // container was still using at the time, and a deleted site never builds
            // again to retry it. Keeping NOTHING is correct here -- the release is dead.
            BuildArtifacts.pruneSuperseded(dockerFor(Map.of()), SiteModel.MODEL_ID.toString(),
                siteId, "");
        } catch (Violations refused) {
            throw refused;
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // -- handler generations --------------------------------------------------

    /** Register {@code handler} as the generation responsible for this site's runtime. */
    public static void activate(int siteId, @NonNull Object handler) {
        ACTIVE_HANDLERS.put(siteId, handler);
    }

    /**
     * @return true when {@code handler} was still the responsible generation (and is now
     *         retired); a superseded generation gets false and must not touch the runtime
     */
    public static boolean deactivate(int siteId, @NonNull Object handler) {
        return ACTIVE_HANDLERS.remove(siteId, handler);
    }

    // -- internals ------------------------------------------------------------

    /**
     * The site's SERVING release, or null. Newest-first so a crash between the two
     * role-flip writes of a switch (both rows briefly serving) resolves to the row the
     * probe just passed; boot recovery completes the flip.
     */
    static @Nullable Row ownedServing(int siteId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(InstanceModel.GENERATED_FOR_ID.eq(siteId))
            .where(InstanceModel.RUNTIME_ROLE.eq(InstanceModel.ROLE_SERVING))
            .where(InstanceModel.DELETED_AT.isNull())
            .orderBy(InstanceModel.ID, SortOrder.DESC)
            .first();
    }

    /** Every live release of the site, whatever its role, newest first. */
    static @NonNull List<Row> ownedInstances(int siteId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(InstanceModel.GENERATED_FOR_ID.eq(siteId))
            .where(InstanceModel.DELETED_AT.isNull())
            .orderBy(InstanceModel.ID, SortOrder.DESC)
            .all();
    }

    private interface ScopedWork<T> {
        T run() throws Exception;
    }

    /** {@link #inScope} for callers with no checked exceptions to surface. */
    static void inScopeUnchecked(int siteId, @NonNull Runnable work) {
        try {
            inScope(siteId, () -> {
                work.run();
                return null;
            });
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Run {@code work} inside the site's GeneratedRows attribution scope. */
    private static <T> T inScope(int siteId, ScopedWork<T> work) throws Exception {
        Object[] result = new Object[1];
        GeneratedRows.as(new GeneratedRows.Attribution(SOURCE,
            SiteModel.MODEL_ID.toString(), siteId), () -> result[0] = work.run());
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    /**
     * Map the SITE settings onto the site_container kind settings. Git-sourced sites
     * ({@code build_context} staged by GitDeployment) build the image in a SANDBOX here
     * and pin the instance to the artifact's DIGEST.
     *
     * AIDEV-NOTE: {@code image} is set to the digest, not to the tag, and that is the
     * point of the wave -- the deployed thing is the built thing. A tag is a mutable
     * pointer: anything that retags {@code hohenheim-site-N:latest} at the daemon (a
     * second controller, an operator, another build) would change what a tag-pinned
     * release runs without changing a single record. {@code built_image_id} keeps its
     * convergence-discriminator job (an unchanged rebuild converges to the same digest
     * and rolls nothing), it is simply no longer the only place the digest appears.
     */
    static @NonNull Map<String, Object> desiredSettings(@NonNull DockerClient docker,
                                                        int siteId,
                                                        @NonNull Map<String, Object> settings) {
        Map<String, Object> desired = new LinkedHashMap<>();

        String buildContext = str(settings.get("build_context"));
        if (!buildContext.isEmpty()) {
            String tag = "hohenheim-site-" + siteId + ":latest";
            SandboxedBuilds.Result build = new SandboxedBuilds(docker).run(new BuildRequest(
                SiteModel.MODEL_ID, siteId, BuildOperationModel.KIND_DOCKERFILE,
                Path.of(buildContext), str(settings.get("dockerfile")), tag,
                // BUILD-time args only. The site's runtime environment_variables are
                // deliberately NOT passed: a build has no business seeing the workload's
                // database password, and the log it produces is tenant-readable.
                EnvVars.toMap(settings.get("build_arguments")),
                str(settings.get("commit_sha")), null, BuildQuota.fromSettings()));
            if (!build.succeeded() || build.imageId() == null) {
                throw Violations.ofField("settings.image", tag,
                    Microcopy.of("site_image_build_failed").withFilter("scope", "violations")
                        .withArg("reason", build.failureReason() != null
                            ? build.failureReason() : build.status()));
            }
            desired.put("image", build.imageId());
            desired.put("built_image_id", build.imageId());
        } else {
            String image = str(settings.get("image"));
            String tag = str(settings.get("tag"));
            String ref = tag.isEmpty() || image.contains(":") ? image : image + ":" + tag;
            // Pin the mutable reference to the content-addressed digest it resolves to
            // RIGHT NOW (pulling once when absent). The digest is what deploys, what a
            // release records and what a rollback re-runs; a retag at the daemon or the
            // registry surfaces as a CHANGED spec (a deliberate release), never as a
            // silent difference between what the record says and what runs.
            if (!ref.isEmpty() && !ref.startsWith("sha256:")) {
                desired.put("image", resolveImageDigest(docker, ref));
                desired.put("image_ref", ref);
            } else {
                desired.put("image", image);
                if (!tag.isEmpty()) {
                    desired.put("tag", tag);
                }
            }
        }

        String command = str(settings.get("command"));
        if (!command.isEmpty()) {
            desired.put("command", command);
        }
        Object port = settings.get("container_port");
        if (port instanceof Number number && number.intValue() > 0) {
            desired.put("container_port", number.intValue());
        }
        String healthPath = str(settings.get("health_path"));
        if (!healthPath.isEmpty()) {
            desired.put("health_path", healthPath);
        }

        // Injected database variables first, operator-authored ones override (the same
        // last-wins order the pre-lowering handler used; still resolves empty for Docker
        // sites until they move onto user-defined networks -- see SiteContainerKind).
        Map<String, String> env = new LinkedHashMap<>(DatabaseEnvInjection.envForSite(siteId));
        env.putAll(EnvVars.toMap(settings.get("environment_variables")));
        if (!env.isEmpty()) {
            desired.put("environment_variables", env);
        }

        Map<String, String> volumes = EnvVars.toMap(settings.get("volumes"));
        if (!volumes.isEmpty()) {
            desired.put("volumes", volumes);
        }

        Object memory = settings.get("memory_limit_mb");
        if (memory instanceof Number number && number.intValue() > 0) {
            desired.put("memory_limit_mb", number.intValue());
        }
        Object cpu = settings.get("cpu_limit");
        if (cpu instanceof Number number && number.doubleValue() > 0) {
            desired.put("cpu_limit", number.doubleValue());
        }
        return desired;
    }

    /**
     * The live status of an unchanged release, reusable ONLY when the daemon attributes
     * the container to this very instance -- a reuse lane that trusted the NAME would
     * hand the proxy a same-named foreign container, the exact takeover
     * OwnerLabels.removeIfOwnedBy exists to refuse on the deploy lane.
     *
     * @return a RUNNING status with the published port, or null when anything (absent,
     *         foreign, stopped, unpublished, unreachable) says "deploy instead"
     */
    private static @Nullable InstanceStatus reusableStatus(@NonNull DockerClient docker,
                                                           int instanceId,
                                                           @NonNull Map<String, Object> desired) {
        String handle = "hohenheim-instance-" + instanceId;
        try {
            Map<String, Object> inspect = docker.inspectContainer(handle);
            Object config = inspect.get("Config");
            OwnerLabels.Owner owner = OwnerLabels.parse(
                config instanceof Map<?, ?> c && c.get("Labels") instanceof Map<?, ?> labels
                    ? labels : null);
            boolean ours = owner != null && owner.model().equals(InstanceModel.MODEL_ID)
                && owner.id().equals(String.valueOf(instanceId));
            boolean running = inspect.get("State") instanceof Map<?, ?> state
                && Boolean.TRUE.equals(state.get("Running"));
            if (!ours || !running) {
                return null;
            }
            Object port = desired.get("container_port");
            if (!(port instanceof Number number) || number.intValue() <= 0) {
                return new InstanceStatus(ContainerState.RUNNING, null, null);
            }
            int hostPort = docker.publishedPort(handle, number.intValue());
            return new InstanceStatus(ContainerState.RUNNING, hostPort, "127.0.0.1");
        } catch (IOException notReusable) {
            return null;
        }
    }

    /**
     * Retire the pre-lowering container exactly once, when the site first gains its
     * owned instance: the legacy {@code hohenheim-site-N} container (site-labelled) is
     * removed if attributably the site's, and the site-owned ledger claims it held are
     * released -- observed when the removal was verified, parked otherwise.
     */
    private static void retireLegacyContainer(@NonNull DockerClient docker, int siteId) {
        try {
            boolean removed = OwnerLabels.removeIfOwnedBy(docker,
                "hohenheim-site-" + siteId, SiteModel.MODEL_ID, siteId);
            if (removed) {
                PortLedger.releaseOwnerObserved(SiteModel.MODEL_ID, siteId);
                Blast.log("SITE-RUNTIME: retired the pre-lowering container of site", siteId);
            } else if (!PortLedger.claimsOf(SiteModel.MODEL_ID, siteId).isEmpty()) {
                // Claims without a container: unverifiable, park them for the reconciler.
                PortLedger.releaseOwner(SiteModel.MODEL_ID, siteId);
            }
        } catch (IOException e) {
            // A foreign same-named container or an unreachable daemon: the deploy that
            // follows fails loudly on its own collision/daemon checks; this retirement
            // never silently destroys what it cannot attribute.
            Blast.log("SITE-RUNTIME: could not retire legacy container of site", siteId,
                "-", e.getMessage());
        }
    }

    static @NonNull Map<String, Object> storedSettings(@NonNull Row instance) {
        Object stored = instance.get(InstanceModel.SETTINGS);
        if (stored instanceof Map<?, ?> map) {
            Map<String, Object> cast = new LinkedHashMap<>();
            map.forEach((key, value) -> cast.put(String.valueOf(key), value));
            return cast;
        }
        return Map.of();
    }

    /**
     * Number-tolerant deep equality: JSON storage round-trips may change the boxed
     * numeric type, and a spurious "changed" would restart the container on every
     * routing reload -- the exact churn convergence exists to remove.
     */
    static boolean settingsEqual(@Nullable Object left, @Nullable Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : a.entrySet()) {
                if (!b.containsKey(entry.getKey())
                        || !settingsEqual(entry.getValue(), b.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!settingsEqual(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    /**
     * The local content-addressed identity of a mutable image reference, pulling once
     * when the daemon does not have it yet.
     *
     * @throws Violations naming the image when it cannot be resolved or pulled
     */
    private static @NonNull String resolveImageDigest(@NonNull DockerClient docker,
                                                      @NonNull String ref) {
        try {
            try {
                return String.valueOf(docker.inspectImage(ref).get("Id"));
            } catch (IOException notLocal) {
                docker.ensureImage(ref, null);
                return String.valueOf(docker.inspectImage(ref).get("Id"));
            }
        } catch (IOException unavailable) {
            throw Violations.ofField("settings.image", ref,
                Microcopy.of("site_image_unresolvable").withFilter("scope", "violations")
                    .withArg("reason", unavailable.getMessage() != null
                        ? unavailable.getMessage() : "daemon unreachable"));
        }
    }

    // Blank/absent server = the local daemon without an inventory lookup (works before
    // the DB is up); anything else goes through THE canonical host-key derivation.
    private static @NonNull DockerClient dockerFor(@NonNull Map<String, Object> settings) {
        Object server = settings.get("server");
        if (server == null || server.toString().isBlank()) {
            return new DockerClient();
        }
        return new ServerService().clientFor(
            ServerModel.nameOf(ServerModel.canonicalServerId(server)));
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
