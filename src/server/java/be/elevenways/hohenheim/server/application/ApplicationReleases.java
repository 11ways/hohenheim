package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.build.BuildArtifacts;
import be.elevenways.hohenheim.server.build.BuildQuota;
import be.elevenways.hohenheim.server.build.BuildRequest;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.InstanceDatabaseNetworks;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The wiring between the APPLICATION record and the canonical runtime-resource contract:
 * every deploy of an application produces an owned {@code hohenheim:release} instance,
 * created/stopped/destroyed through {@link InstanceService}. The application record keeps
 * the source, the build, the variables and the volumes and never owns a container itself.
 *
 * AIDEV-NOTE: this class was {@code SiteInstances} and was keyed to the SITE (phase-0 brief
 * 7). The re-key is what makes an application a first-class record: a site is now merely a
 * hostname pointing AT an application ({@code sites.instance_id} + the {@code instance}
 * upstream kind), several sites may point at the same one, and an application with no site
 * at all still deploys, rolls back and keeps its volumes.
 *
 * AIDEV-NOTE: entries here run inside {@code GeneratedRows.as(...)} DELIBERATELY. The
 * application record's own authority gates already judged the write that leads here; the
 * runtime convergence that follows is system work, exactly like the ACME publisher. Without
 * the scope, a tenant deploying their (granted) application would be re-asked for INSTANCE
 * capabilities they structurally cannot hold.
 */
public final class ApplicationReleases {

    /** The GeneratedRows source token, and the Accountability origin of every write here. */
    public static final String SOURCE = "application";

    /** Where a release stores the owner-keyed host directories it must mount. */
    public static final String VOLUME_MOUNTS = "volume_mounts";

    private ApplicationReleases() {
    }

    /** The outcome of one convergence pass: the release instance and its live status. */
    public record Release(int instanceId, @NonNull InstanceStatus status) {
    }

    /**
     * Install the ownership funnel on the instance write pipeline (MODULES stage).
     *
     * AIDEV-NOTE: the mechanics live in {@link OwnedInstances} -- the guard, the
     * generated-only kind refusal and the scope are shared by every owning tier. What keeps
     * {@code release} unwritable from the admin form is {@code ReleaseKind.generatedOnly()},
     * a declaration on the kind rather than a name in a hook.
     */
    public static synchronized void install() {
        OwnedInstances.install();
    }

    /**
     * Converge an application's owned release onto the resolved settings: build the image
     * for a git-sourced application, then deploy ONLY when the desired settings changed or
     * the workload is not running -- an unchanged, running release is reused.
     *
     * AIDEV-NOTE: the whole pass -- read the serving release, resolve/build the spec, flip
     * the roles, schedule the drain -- runs under the application's convergence lock, and
     * that read-to-flip span is exactly what the lock is for. Without it a webhook and a
     * Deploy button (or two pushes) both read the SAME serving release, both build, and
     * both flip: two rows end up role {@code serving}, {@code ownedServing} picks the
     * newer, and the older runs forever -- no sweeper walks a second serving row, and it
     * keeps its port claim and its booked capacity.
     *
     * AIDEV-NOTE: the second converge WAITS, it does not coalesce. Waiting is the honest
     * default: a push of commit N+1 arriving during the release of N must still deploy
     * N+1, and only the queued caller's own source can answer whether an in-flight
     * converge is equivalent to it. The cost of waiting is already paid for by the fast
     * lane below -- the waiter re-reads the freshly flipped release, finds its source
     * fingerprint equal and returns it without resolving a spec, so a duplicate deploy
     * costs one daemon inspect rather than a second build.
     *
     * The lock is NEVER held across the drain: {@code scheduleDrain} only hands work to a
     * virtual thread, and that thread sleeps out the drain window after this method (and
     * the lock) has returned.
     *
     * @param overrides source facts resolved outside the record (a fresh checkout's
     *        {@code build_context} and {@code commit_sha}); empty for a plain converge
     * @throws Violations naming the refusal (quota, image, fence, daemon)
     */
    public static @NonNull Release converge(int applicationId,
                                            @NonNull Map<String, Object> overrides) {
        synchronized (ConvergenceLocks.forApplication(applicationId)) {
            return convergeLocked(applicationId, overrides);
        }
    }

    /** {@link #converge}'s body; callers hold the application's convergence lock. */
    private static @NonNull Release convergeLocked(int applicationId,
                                                   @NonNull Map<String, Object> overrides) {

        Row application = requireApplication(applicationId);
        Map<String, Object> settings = resolvedSettings(application, overrides);
        String applicationName = application.get(InstanceModel.NAME);
        int serverId = ServerModel.canonicalServerId(application.get(InstanceModel.SERVER_ID));
        DockerClient docker = dockerFor(serverId);
        String fingerprint = ReleaseEngine.sourceFingerprint(applicationId, settings);

        try {
            return inScope(applicationId, () -> {
                Row serving = ownedServing(applicationId);

                // A build context WITHOUT a commit identity has no source fingerprint a
                // reload can trust (the content can change under the same path), so it
                // always resolves the spec.
                boolean fingerprintable = str(settings.get("build_context")).isEmpty()
                    || !str(settings.get("commit_sha")).isEmpty();

                // Fast lane: unchanged source, running workload -- reuse without even
                // resolving the spec (which for a git-sourced application means a sandbox
                // build). The fingerprint IS the "would a release change anything" test.
                if (fingerprintable && serving != null
                        && InstanceModel.STATUS_RUNNING.equals(serving.get(InstanceModel.STATUS))
                        && fingerprint.equals(
                            storedSettings(serving).get("source_fingerprint"))
                        && serverId == ServerModel.canonicalServerId(
                            serving.get(InstanceModel.SERVER_ID))) {
                    int servingId = serving.get(InstanceModel.ID);
                    InstanceStatus live =
                        reusableStatus(docker, servingId, storedSettings(serving));
                    if (live != null) {
                        return new Release(servingId, live);
                    }
                }

                // Rollback pin: the operator rejected exactly this source, so converge
                // on the ROLLED-BACK release instead of re-releasing the rejected spec.
                if (serving != null && ReleaseEngine.pinnedByRollback(applicationId, fingerprint)) {
                    int servingId = serving.get(InstanceModel.ID);
                    if (InstanceModel.STATUS_RUNNING.equals(serving.get(InstanceModel.STATUS))) {
                        InstanceStatus live =
                            reusableStatus(docker, servingId, storedSettings(serving));
                        if (live != null) {
                            return new Release(servingId, live);
                        }
                    }
                    return new Release(servingId, new InstanceService().deploy(servingId));
                }

                if (serving == null) {
                    Map<String, Object> desired = desiredSettings(docker, application, settings);
                    desired.put("source_fingerprint", fingerprint);
                    return ReleaseEngine.initialRelease(applicationId, applicationName, serverId,
                        desired, fingerprint);
                }

                // Changed source over an existing release: the health-gated lane.
                return ReleaseEngine.release(docker, applicationId, applicationName, serverId,
                    serving, settings, fingerprint);
            });
        } catch (Violations refused) {
            throw refused;
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Live status of the application's SERVING release; null when there is none. */
    public static @Nullable InstanceStatus liveStatus(int applicationId) {
        Row instance = ownedServing(applicationId);
        if (instance == null) {
            return null;
        }
        return new InstanceService().liveStatus(instance.get(InstanceModel.ID));
    }

    /**
     * Stop the application's running releases -- the serving one plus any still-draining
     * retired one. Never throws: a failed stop leaves the claims parked by
     * InstanceService.stop and the reconciler surfaces the rest.
     */
    public static void stopFor(int applicationId) {
        try {
            inScope(applicationId, () -> {
                for (Row instance : ownedInstances(applicationId)) {
                    if (InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS))
                            || InstanceModel.STATUS_STARTING.equals(
                                instance.get(InstanceModel.STATUS))) {
                        new InstanceService().stop(instance.get(InstanceModel.ID));
                    }
                }
                return null;
            });
        } catch (Exception e) {
            Blast.log("APPLICATION: stop of application", applicationId, "failed -",
                e.getMessage());
        }
        ApplicationUpstreams.invalidate(applicationId);
    }

    /**
     * Verified end of life, called by the application's delete path. EVERY release dies
     * here -- serving, retained rollback target and any in-flight candidate alike.
     *
     * AIDEV-NOTE: the VOLUMES deliberately survive. The record is soft-deleted and therefore
     * restorable, so removing the directories here would make a restore come back to empty
     * storage; the site tier's own delete path made the same call for the same reason. The
     * reconciler surfaces a soft-deleted owner's volumes as orphans, which is an operator
     * decision with the data still on disk.
     *
     * @throws Violations when the daemon cannot confirm a teardown; the delete is refused
     *         rather than leaving a running container behind a dead record
     */
    public static void destroyFor(int applicationId) {
        List<Row> owned = ownedInstances(applicationId);
        ApplicationUpstreams.invalidate(applicationId);
        // Previews are releases of THIS application on their own hostnames; they cannot
        // outlive the record whose source they build (the soft delete fires no remove hook
        // that would ever reclaim them).
        PreviewDeployments.destroyForApplication(applicationId);
        if (owned.isEmpty()) {
            return;
        }
        // Captured BEFORE the destroys soft-delete the rows the derivation reads.
        Set<Integer> servers = new LinkedHashSet<>();
        for (Row instance : owned) {
            servers.add(ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID)));
        }
        try {
            inScope(applicationId, () -> {
                for (Row instance : owned) {
                    new InstanceService().destroy(instance.get(InstanceModel.ID));
                }
                return null;
            });
            // Every release container is gone, so the application's database link networks
            // have no consumer left to disconnect: remove them all, verified.
            InstanceDatabaseNetworks.sweepFor(applicationId, true, servers);
            // The workload is gone, so nothing holds the build artifacts any more.
            BuildArtifacts.pruneSuperseded(dockerFor(ServerModel.localServerId()),
                InstanceModel.MODEL_ID.toString(), applicationId, "");
        } catch (Violations refused) {
            throw refused;
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // -- release ownership ----------------------------------------------------

    /**
     * The application's SERVING release, or null. Newest-first so a crash between the two
     * role-flip writes of a switch (both rows briefly serving) resolves to the row the
     * probe just passed; boot recovery completes the flip.
     */
    public static @Nullable Row ownedServing(int applicationId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(InstanceModel.GENERATED_FOR_ID.eq(applicationId))
            .where(InstanceModel.RUNTIME_ROLE.eq(InstanceModel.ROLE_SERVING))
            .where(InstanceModel.DELETED_AT.isNull())
            .orderBy(InstanceModel.ID, SortOrder.DESC)
            .first();
    }

    /** Every live release of the application, whatever its role, newest first. */
    public static @NonNull List<Row> ownedInstances(int applicationId) {
        return OwnedInstances.ownedBy(InstanceModel.MODEL_ID, applicationId);
    }

    /**
     * WHICH record owns the database links and volumes of a deploying workload: a release
     * carries neither, its application carries both.
     *
     * AIDEV-NOTE: this is the site lane's "resolve the serving release" asymmetry, kept but
     * inverted -- the consumer resolves its OWNER instead of the owner resolving its
     * consumer, which is answerable from the row already in hand and needs no query.
     */
    public static int linkOwnerOf(@NonNull Row instance) {
        Integer ownerId = instance.get(InstanceModel.GENERATED_FOR_ID);
        boolean generatedByApplication = ownerId != null
            && InstanceModel.MODEL_ID.toString().equals(
                instance.get(InstanceModel.GENERATED_FOR_MODEL));
        return generatedByApplication ? ownerId : instance.get(InstanceModel.ID);
    }

    /**
     * The instance whose CONTAINER consumes an owner's links: an application's serving
     * release, any other instance itself.
     *
     * @return null when a release-managed owner has nothing serving
     */
    public static @Nullable Integer consumerInstanceOf(int ownerId) {
        Row owner = Models.get(InstanceModel.class).findById(ownerId);
        if (owner == null) {
            return null;
        }
        if (!InstanceKinds.isReleaseManaged(owner.get(InstanceModel.KIND))) {
            return ownerId;
        }
        Row serving = ownedServing(ownerId);
        return serving == null ? null : serving.get(InstanceModel.ID);
    }

    /** Run {@code work} inside the application's GeneratedRows attribution scope. */
    public static void inScopeUnchecked(int applicationId, @NonNull Runnable work) {
        OwnedInstances.inScopeUnchecked(SOURCE, InstanceModel.MODEL_ID, applicationId, work);
    }

    private static <T> T inScope(int applicationId, OwnedInstances.ScopedWork<T> work)
            throws Exception {
        return OwnedInstances.inScope(SOURCE, InstanceModel.MODEL_ID, applicationId, work);
    }

    // -- the spec -------------------------------------------------------------

    /** The application's stored settings with the caller's resolved source facts folded in. */
    public static @NonNull Map<String, Object> resolvedSettings(
            @NonNull Row application, @NonNull Map<String, Object> overrides) {
        Map<String, Object> settings = new LinkedHashMap<>(storedSettings(application));
        settings.putAll(overrides);
        return settings;
    }

    /**
     * Map the APPLICATION settings onto the release kind settings. Git-sourced applications
     * (a {@code build_context} staged by {@link ApplicationDeploys}) build the image in a
     * SANDBOX here and pin the release to the artifact's DIGEST.
     *
     * AIDEV-NOTE: {@code image} is set to the digest, not to the tag. A tag is a mutable
     * pointer: anything that retags at the daemon would change what a tag-pinned release
     * runs without changing a single record.
     *
     * @throws Violations {@code application_never_built} when the application names a
     *         repository but no checkout was staged and no release exists to reuse -- the
     *         honest answer to "route this site" before anybody deployed
     */
    static @NonNull Map<String, Object> desiredSettings(@NonNull DockerClient docker,
                                                        @NonNull Row application,
                                                        @NonNull Map<String, Object> settings) {
        int applicationId = application.get(InstanceModel.ID);
        Map<String, Object> desired = new LinkedHashMap<>();

        String buildContext = str(settings.get("build_context"));
        if (!buildContext.isEmpty()) {
            String tag = ControllerScope.handle(ControllerScope.KIND_INSTANCE, applicationId)
                + ":latest";
            SandboxedBuilds.Result build = new SandboxedBuilds(docker,
                serverNameOf(application)).run(new BuildRequest(
                InstanceModel.MODEL_ID, applicationId,
                BuildOperationModel.kindOrDefault(settings.get("builder")),
                Path.of(buildContext), str(settings.get("dockerfile")), tag,
                // BUILD-time args only. The runtime environment_variables are deliberately
                // NOT passed: a build has no business seeing the workload's database
                // password, and the log it produces is tenant-readable.
                EnvVars.toMap(settings.get("build_arguments")),
                str(settings.get("commit_sha")), null, BuildQuota.fromSettings()));
            if (!build.succeeded() || build.imageId() == null) {
                throw Violations.ofField("settings.image", tag,
                    Microcopy.of("application_image_build_failed")
                        .withFilter("scope", "violations")
                        .withArg("reason", build.failureReason() != null
                            ? build.failureReason() : build.status()));
            }
            desired.put("image", build.imageId());
            desired.put("built_image_id", build.imageId());
            desired.put("commit_sha", str(settings.get("commit_sha")));
        } else {
            String image = str(settings.get("image"));
            String tag = str(settings.get("tag"));
            String ref = tag.isEmpty() || image.contains(":") ? image : image + ":" + tag;
            if (ref.isEmpty()) {
                throw Violations.ofForm(Microcopy.of("application_never_built")
                    .withFilter("scope", "violations")
                    .withArg("name", str(application.get(InstanceModel.NAME))));
            }
            // Pin the mutable reference to the content-addressed digest it resolves to
            // RIGHT NOW (pulling once when absent). A retag at the daemon or the registry
            // surfaces as a CHANGED spec (a deliberate release), never as a silent
            // difference between what the record says and what runs.
            if (!ref.startsWith("sha256:")) {
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

        // Injected database variables first, operator-authored ones override. The release
        // container joins each attached database's link network before start
        // (InstanceDatabaseNetworks, on the InstanceService.deploy seam) and dials the
        // database by container hostname -- 127.0.0.1 inside the container is itself.
        Map<String, String> env = new LinkedHashMap<>(
            DatabaseEnvInjection.envForInstance(applicationId, null));
        env.putAll(EnvVars.toMap(settings.get("environment_variables")));
        if (!env.isEmpty()) {
            desired.put("environment_variables", env);
        }

        // The APPLICATION's volume directories, mounted into every release it generates.
        // Resolved here (host directories created, quotas applied) and stored on the
        // release, so a rollback re-mounts exactly what it stored.
        Map<String, String> mounts = InstanceVolumes.mountsFor(applicationId,
            serverNameOf(application));
        if (!mounts.isEmpty()) {
            desired.put(VOLUME_MOUNTS, mounts);
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
     * The live status of an unchanged release, reusable ONLY when the daemon attributes the
     * container to this very instance -- a reuse lane that trusted the NAME would hand the
     * proxy a same-named foreign container.
     *
     * @return a RUNNING status with the published port, or null when anything (absent,
     *         foreign, stopped, unpublished, unreachable) says "deploy instead"
     */
    private static @Nullable InstanceStatus reusableStatus(@NonNull DockerClient docker,
                                                           int instanceId,
                                                           @NonNull Map<String, Object> desired) {
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        try {
            Map<String, Object> inspect = docker.inspectContainer(handle);
            Object config = inspect.get("Config");
            OwnerLabels.Owner owner = OwnerLabels.parse(
                config instanceof Map<?, ?> c && c.get("Labels") instanceof Map<?, ?> labels
                    ? labels : null);
            boolean ours = owner != null && owner.model().equals(InstanceModel.MODEL_ID)
                && owner.id().equals(String.valueOf(instanceId));
            Map<?, ?> state = inspect.get("State") instanceof Map<?, ?> s ? s : Map.of();
            boolean running = Boolean.TRUE.equals(state.get("Running"));
            // A container whose cgroup lost a process to the OOM killer is NOT reusable,
            // however running it looks: reuse would hand the proxy an upstream whose
            // workload is already dead.
            boolean oomKilled = Boolean.TRUE.equals(state.get("OOMKilled"));
            if (!ours || !running || oomKilled) {
                return null;
            }
            Object port = desired.get("container_port");
            if (!(port instanceof Number number) || number.intValue() <= 0) {
                return new InstanceStatus(ContainerState.RUNNING, null);
            }
            int hostPort = docker.publishedPort(handle, number.intValue());
            return new InstanceStatus(ContainerState.RUNNING, hostPort, "127.0.0.1");
        } catch (IOException notReusable) {
            return null;
        }
    }

    public static @NonNull Map<String, Object> storedSettings(@NonNull Row instance) {
        Object stored = instance.get(InstanceModel.SETTINGS);
        if (stored instanceof Map<?, ?> map) {
            Map<String, Object> cast = new LinkedHashMap<>();
            map.forEach((key, value) -> cast.put(String.valueOf(key), value));
            return cast;
        }
        return Map.of();
    }

    /**
     * Number-tolerant deep equality: JSON storage round-trips may change the boxed numeric
     * type, and a spurious "changed" would restart the container on every converge.
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
     * The local content-addressed identity of a mutable image reference, pulling once when
     * the daemon does not have it yet.
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
                Microcopy.of("application_image_unresolvable")
                    .withFilter("scope", "violations")
                    .withArg("reason", unavailable.getMessage() != null
                        ? unavailable.getMessage() : "daemon unreachable"));
        }
    }

    /**
     * The application record behind an id.
     *
     * @throws Violations when the record is gone, deleted, or not an application
     */
    public static @NonNull Row requireApplication(int applicationId) {
        Row application = Models.get(InstanceModel.class).findById(applicationId);
        if (application == null || application.get(InstanceModel.DELETED_AT) != null
                || !InstanceKinds.isReleaseManaged(application.get(InstanceModel.KIND))) {
            throw Violations.ofForm(Microcopy.of("application_not_found")
                .withFilter("scope", "violations").withArg("id", applicationId));
        }
        return application;
    }

    /** The inventoried server name an application record resolves to. */
    public static @NonNull String serverNameOf(@NonNull Row application) {
        return ServerModel.nameOf(
            ServerModel.canonicalServerId(application.get(InstanceModel.SERVER_ID)));
    }

    static @NonNull DockerClient dockerFor(int serverId) {
        return serverId == ServerModel.localServerId()
            ? new DockerClient()
            : new ServerService().clientFor(ServerModel.nameOf(serverId));
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
