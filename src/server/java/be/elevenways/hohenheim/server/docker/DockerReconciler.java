package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.stack.StackDeployer;
import be.elevenways.hohenheim.server.util.PortProbe;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Attributes every container, volume and network on a Docker daemon to its owning
 * record and REPORTS the result -- it never removes a Docker resource and never
 * applies a label. The classifier is pure (unit-testable without a daemon, the
 * DockerReclaim discipline); the sweeper lists one daemon, classifies, and REPLACES
 * that server's stored findings, which the dashboard attention list reads. Its ONE
 * deletion authority (C6) is over LEDGER ROWS, not Docker resources: a port claim
 * parked in {@code releasing} whose port this sweep OBSERVED free is deleted, closing
 * the reserved-until-observed loop.
 *
 * AIDEV-NOTE: Authority limits are deliberate and load-bearing. Removal: an orphan
 * VOLUME is the one unrecoverable resource (DockerReclaim refuses volumes for the
 * same reason), so removal stays an explicit operator action -- which now EXISTS as
 * OrphanActions.removeOrphan (containers/networks, re-verified live, ActivityLog-
 * recorded, surfaced on ReconcileFindingResource). Labelling: stamping a label on a
 * NAME match would convert "looks like ours" into "is ours"; label ADOPTION still
 * does not exist because Docker cannot relabel a container or volume in place --
 * adoption means recreate-under-labels, which only the owning tier can do.
 */
public final class DockerReconciler {

    /** Attribution buckets; the model owns the persisted token vocabulary. */
    public enum Bucket {
        OWNED(ReconcileFindingModel.BUCKET_OWNED),
        ORPHANED(ReconcileFindingModel.BUCKET_ORPHANED),
        FOREIGN_KNOWN(ReconcileFindingModel.BUCKET_FOREIGN_KNOWN),
        FOREIGN_COLLIDING(ReconcileFindingModel.BUCKET_FOREIGN_COLLIDING),
        FOREIGN_UNRELATED(ReconcileFindingModel.BUCKET_FOREIGN_UNRELATED);

        public final String token;

        Bucket(String token) {
            this.token = token;
        }
    }

    /** What the attribution was based on. */
    public enum Evidence { OWNER_LABEL, STACK_LABEL, NAME, FOREIGN_LABEL, NONE }

    /** One classified resource. */
    public record Finding(@NonNull String kind, @NonNull String name, @NonNull Bucket bucket,
                          @NonNull Evidence evidence, OwnerLabels.@Nullable Owner owner,
                          @Nullable String detail) {}

    /** The classifier's only window on the database, so the decision stays pure. */
    public interface Records {

        /** @return whether a live (not soft-deleted) record of this model has this primary key */
        boolean liveById(@NonNull Identifier model, @NonNull String id);

        /** @return whether a live record of this model has this natural name (stacks, databases) */
        boolean liveByName(@NonNull Identifier model, @NonNull String name);

        /**
         * @return the identity token of the controller this classification speaks for; a
         *         resource carrying any other token belongs to a DIFFERENT controller,
         *         whatever its record id says
         */
        @NonNull String controllerToken();
    }

    /**
     * Label-key prefixes of recognised third-party conventions. A match means
     * "someone else's, deliberately": it must never raise an alarm, or every dev
     * machine's attention list opens with testcontainers noise and gets ignored.
     */
    private static final List<String> KNOWN_FOREIGN_LABEL_PREFIXES = List.of(
        "org.testcontainers",
        "be.elevenways.zenit.testdatasources",
        "com.docker.compose.project",
        "com.docker.volume.anonymous");

    /** Docker's own networks: present on every daemon, never anyone's. */
    private static final Set<String> BUILTIN_NETWORKS =
        Set.of("bridge", "host", "none", "ingress", "docker_gwbridge");

    public static final String KIND_CONTAINER = "container";
    public static final String KIND_VOLUME = "volume";
    public static final String KIND_NETWORK = "network";

    private DockerReconciler() {}

    // -- classification (pure) ------------------------------------------------

    /**
     * Attribute one resource. Precedence: an explicit owner-label claim, then the
     * stack-name label (pre-owner-label stack resources), then recognised foreign
     * conventions, then our own NAMING schemes (existing volumes can never be
     * relabelled, so name-based attribution is what keeps a pre-label volume whose
     * record died visible as an orphan), then the hohenheim- prefix as a collision.
     *
     * AIDEV-NOTE: the controller check comes BEFORE the record lookup and it must stay
     * there. A record id only means something inside the database that allocated it, so
     * asking "is #1 live?" about another controller's resource is asking the wrong
     * database -- and answering yes is what let one controller treat another's running
     * workload as its own.
     */
    public static @NonNull Finding classify(@NonNull String kind, @NonNull String name,
                                            @Nullable Map<?, ?> labels, @NonNull Records records) {
        OwnerLabels.Owner owner = OwnerLabels.parse(labels);
        if (owner != null) {
            if (!records.controllerToken().equals(owner.controller())) {
                return new Finding(kind, name, Bucket.FOREIGN_COLLIDING, Evidence.OWNER_LABEL,
                    owner, owner.controller() != null
                        ? "another hohenheim controller '" + owner.controller() + "' owns this as "
                            + owner.model() + " #" + owner.id()
                        : "pre-namespace hohenheim resource (no controller label): its record id"
                            + " cannot be attributed to any controller");
            }
            boolean live = records.liveById(owner.model(), owner.id());
            return new Finding(kind, name, live ? Bucket.OWNED : Bucket.ORPHANED,
                Evidence.OWNER_LABEL, owner, owner.model() + " #" + owner.id());
        }

        if (labels != null && labels.get(StackDeployer.LABEL_STACK) instanceof String stackName
            && !stackName.isBlank()) {
            boolean live = records.liveByName(StackModel.MODEL_ID, stackName);
            return new Finding(kind, name, live ? Bucket.OWNED : Bucket.ORPHANED,
                Evidence.STACK_LABEL, null, "stack '" + stackName + "'");
        }

        String foreign = knownForeignConvention(labels);
        if (foreign != null) {
            return new Finding(kind, name, Bucket.FOREIGN_KNOWN, Evidence.FOREIGN_LABEL, null, foreign);
        }
        if (KIND_NETWORK.equals(kind) && BUILTIN_NETWORKS.contains(name)) {
            return new Finding(kind, name, Bucket.FOREIGN_KNOWN, Evidence.NAME, null, "docker built-in");
        }

        Finding byName = classifyByNamingScheme(kind, name, records);
        if (byName != null) {
            return byName;
        }

        if (name.startsWith(ControllerScope.PREFIX + "-")) {
            ControllerScope.Scoped scoped = ControllerScope.parse(name);
            String detail;
            if (scoped == null) {
                detail = "hohenheim-prefixed but carries no controller namespace"
                    + " (a pre-namespace resource, or a stranger's)";
            } else if (!scoped.token().equals(records.controllerToken())) {
                detail = "minted by another hohenheim controller '" + scoped.token() + "'";
            } else {
                detail = "hohenheim-prefixed but matches no naming scheme";
            }
            return new Finding(kind, name, Bucket.FOREIGN_COLLIDING, Evidence.NAME, null, detail);
        }
        return new Finding(kind, name, Bucket.FOREIGN_UNRELATED, Evidence.NONE, null, null);
    }

    /**
     * Attribution by our own naming conventions, for resources created before the
     * owner labels existed. Sites key on the record id in the name; databases and
     * stacks key on their unique name column.
     *
     * AIDEV-NOTE: there is deliberately NO {@code instance} scheme here. The instance
     * tier was born AFTER the owner labels, so every genuine instance resource
     * (container AND its volumes) carries them from birth -- a label-less
     * instance-shaped name is by definition not attributably ours and correctly falls
     * through to FOREIGN_COLLIDING. Adding a name fallback would convert "looks like
     * ours" into "is ours" for a tier that never needs it.
     *
     * AIDEV-NOTE: name attribution now runs ONLY on names carrying OUR controller token.
     * A name-keyed scheme reads the record's own name column, which another controller's
     * database can hold too, so an unscoped or foreign-scoped name is never resolved
     * against our records; it falls through to the collision bucket instead.
     */
    private static @Nullable Finding classifyByNamingScheme(String kind, String name, Records records) {
        ControllerScope.Scoped scoped = ControllerScope.parse(name);
        if (scoped == null || !scoped.token().equals(records.controllerToken())) {
            return null;
        }
        String scheme = ControllerScope.PREFIX + "-" + scoped.token() + "-";

        // {scope}site-{id} containers, {scope}site-{id}-vol-{mount} volumes.
        if (name.startsWith(scheme + "site-")) {
            String rest = name.substring((scheme + "site-").length());
            int volMarker = rest.indexOf("-vol-");
            String id = volMarker >= 0 ? rest.substring(0, volMarker) : rest;
            boolean volumeShaped = volMarker >= 0 && KIND_VOLUME.equals(kind);
            boolean containerShaped = volMarker < 0 && KIND_CONTAINER.equals(kind);
            if ((volumeShaped || containerShaped) && id.chars().allMatch(Character::isDigit)
                && !id.isEmpty()) {
                return nameFinding(kind, name, records.liveById(SiteModel.MODEL_ID, id),
                    new OwnerLabels.Owner(SiteModel.MODEL_ID, id, records.controllerToken()));
            }
            return null;   // hohenheim-prefixed, wrong shape -> colliding
        }

        // {scope}db-{name} containers, {scope}db-{name}-data volumes.
        if (name.startsWith(scheme + "db-")) {
            String rest = name.substring((scheme + "db-").length());
            if (KIND_VOLUME.equals(kind) && rest.endsWith("-data")) {
                String dbName = rest.substring(0, rest.length() - "-data".length());
                return nameFinding(kind, name,
                    records.liveByName(DatabaseModel.MODEL_ID, dbName), null);
            }
            if (KIND_CONTAINER.equals(kind)) {
                return nameFinding(kind, name,
                    records.liveByName(DatabaseModel.MODEL_ID, rest), null);
            }
            return null;
        }

        // {scope}stack-{name} networks, {scope}stack-{name}-{suffix} containers
        // and volumes. The stack name may itself contain dashes, so containers and
        // volumes probe every split point (longest candidate first).
        if (name.startsWith(scheme + "stack-")) {
            String rest = name.substring((scheme + "stack-").length());
            if (KIND_NETWORK.equals(kind)) {
                return nameFinding(kind, name, records.liveByName(StackModel.MODEL_ID, rest), null);
            }
            for (int cut = rest.length(); cut > 0; cut = rest.lastIndexOf('-', cut - 1)) {
                String candidate = rest.substring(0, cut);
                if (records.liveByName(StackModel.MODEL_ID, candidate)) {
                    return nameFinding(kind, name, true, null);
                }
            }
            // Stack-scheme-shaped with no live stack: the record is gone -- the
            // exact situation the orphan bucket exists to surface.
            return nameFinding(kind, name, false, null);
        }

        return null;
    }

    private static Finding nameFinding(String kind, String name, boolean live,
                                       OwnerLabels.@Nullable Owner owner) {
        return new Finding(kind, name, live ? Bucket.OWNED : Bucket.ORPHANED, Evidence.NAME,
            owner, live ? "unlabelled; the label lands on the next recreate" : "no live record");
    }

    private static @Nullable String knownForeignConvention(@Nullable Map<?, ?> labels) {
        if (labels == null) {
            return null;
        }
        for (Object key : labels.keySet()) {
            String text = String.valueOf(key);
            for (String prefix : KNOWN_FOREIGN_LABEL_PREFIXES) {
                if (text.equals(prefix) || text.startsWith(prefix + ".")) {
                    return prefix;
                }
            }
        }
        return null;
    }

    /**
     * Classify raw daemon listings ({@code /containers/json?all=true}, {@code /volumes},
     * {@code /networks} shapes, as {@link DockerClient} returns them).
     */
    public static @NonNull List<Finding> classifyAll(@NonNull List<Object> containers,
                                                     @NonNull List<Object> volumes,
                                                     @NonNull List<Object> networks,
                                                     @NonNull Records records) {
        List<Finding> findings = new ArrayList<>();
        for (Object entry : containers) {
            if (entry instanceof Map<?, ?> summary) {
                String name = containerSummaryName(summary);
                if (name != null) {
                    findings.add(classify(KIND_CONTAINER, name,
                        summary.get("Labels") instanceof Map<?, ?> labels ? labels : null, records));
                }
            }
        }
        for (Object entry : volumes) {
            if (entry instanceof Map<?, ?> volume && volume.get("Name") != null) {
                findings.add(classify(KIND_VOLUME, String.valueOf(volume.get("Name")),
                    volume.get("Labels") instanceof Map<?, ?> labels ? labels : null, records));
            }
        }
        for (Object entry : networks) {
            if (entry instanceof Map<?, ?> network && network.get("Name") != null) {
                findings.add(classify(KIND_NETWORK, String.valueOf(network.get("Name")),
                    network.get("Labels") instanceof Map<?, ?> labels ? labels : null, records));
            }
        }
        return findings;
    }

    private static @Nullable String containerSummaryName(Map<?, ?> summary) {
        if (summary.get("Names") instanceof List<?> names && !names.isEmpty()) {
            String name = String.valueOf(names.get(0));
            return name.startsWith("/") ? name.substring(1) : name;
        }
        return null;
    }

    // -- the live record resolver --------------------------------------------

    /** {@link Records} answered by the actual models; site liveness honours soft delete. */
    public static final class ModelRecords implements Records {

        @Override
        public @NonNull String controllerToken() {
            return ControllerIdentity.token();
        }

        @Override
        public boolean liveById(@NonNull Identifier model, @NonNull String id) {
            Integer key = parseInt(id);
            if (key == null) {
                return false;
            }
            if (SiteModel.MODEL_ID.equals(model)) {
                return Models.get(SiteModel.class).find()
                    .where(SiteModel.ID.eq(key))
                    .where(SiteModel.DELETED_AT.isNull())
                    .first() != null;
            }
            if (DatabaseModel.MODEL_ID.equals(model)) {
                return Models.get(DatabaseModel.class).find()
                    .where(DatabaseModel.ID.eq(key)).first() != null;
            }
            if (StackModel.MODEL_ID.equals(model)) {
                return Models.get(StackModel.class).find()
                    .where(StackModel.ID.eq(key)).first() != null;
            }
            if (InstanceModel.MODEL_ID.equals(model)) {
                return Models.get(InstanceModel.class).find()
                    .where(InstanceModel.ID.eq(key))
                    .where(InstanceModel.DELETED_AT.isNull())
                    .first() != null;
            }
            // Site-database link networks are owned by their ATTACHMENT row: the row's
            // deletion is what orphans the network, so a stale join a sweep missed is
            // surfaced instead of sitting OWNED forever.
            if (SiteDatabaseModel.MODEL_ID.equals(model)) {
                return Models.get(SiteDatabaseModel.class).find()
                    .where(SiteDatabaseModel.ID.eq(key)).first() != null;
            }
            return false;   // a model we cannot resolve is an alarm, not an assumption
        }

        @Override
        public boolean liveByName(@NonNull Identifier model, @NonNull String name) {
            if (DatabaseModel.MODEL_ID.equals(model)) {
                return Models.get(DatabaseModel.class).findByName(name) != null;
            }
            if (StackModel.MODEL_ID.equals(model)) {
                return Models.get(StackModel.class).find()
                    .where(StackModel.NAME.eq(name)).first() != null;
            }
            return false;
        }

        private static @Nullable Integer parseInt(String id) {
            try {
                return Integer.parseInt(id);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // -- sweeping and persistence ---------------------------------------------

    /**
     * Sweep every inventoried DOCKER server. A server whose daemon cannot be listed keeps
     * its PREVIOUS findings (a sweep that cannot see the truth reports nothing new,
     * the DockerReclaim rule) and the failure is logged. An Incus host is not swept and
     * is not probed: this sweep doubles as the Docker-tier heartbeat, and a host it
     * cannot address by construction is not a host it may report on.
     */
    public static @NonNull Map<String, List<Finding>> sweepAll(@NonNull ServerService servers) {
        servers.ensureLocal();
        Map<String, List<Finding>> results = new LinkedHashMap<>();
        for (String serverName : servers.dockerNames()) {
            try {
                results.put(serverName, sweepServer(serverName, servers.clientFor(serverName)));
                // The sweep IS a successful daemon contact: the scheduled reconcile
                // doubles as the host heartbeat, so last_seen_at stays honest without
                // a second per-host probe loop.
                HostProbe.recordSuccess(serverName);
            } catch (Exception e) {
                Blast.log("DOCKER RECONCILE: could not sweep server", serverName, "-", e.getMessage());
                HostProbe.recordFailure(serverName,
                    HostProbe.classify(e));
            }
        }
        return results;
    }

    /**
     * List one daemon, classify everything on it, replace that server's stored
     * findings, and free the {@code releasing} port claims this sweep OBSERVED free.
     *
     * @throws IOException when the daemon cannot be listed; stored findings are kept
     */
    public static @NonNull List<Finding> sweepServer(@NonNull String serverName,
                                                     @NonNull DockerClient docker) throws IOException {
        List<Object> containers = docker.listContainers(true);
        List<Finding> findings = classifyAll(
            containers, docker.listVolumes(), docker.listNetworks(),
            new ModelRecords());
        store(serverName, findings);
        sweepReleasingClaims(serverName, containers, PortProbe::isFree);
        return findings;
    }

    // -- the releasing-claim observer -----------------------------------------

    /** One published (bind address, host port, protocol) binding read off a listing. */
    public record PublishedPort(@NonNull String address, int port, @NonNull String protocol) {}

    /** What a releasing sweep did: claims observed free (deleted) vs still bound (kept). */
    public record ReleasingSweep(int released, int retained) {}

    /** Local-host bind probe seam, injectable so the decision is testable without binding. */
    public interface Probe {
        boolean isFree(@NonNull String address, int port, @NonNull String protocol);
    }

    /**
     * The published host-port bindings of a {@code /containers/json?all=true} listing.
     * Stopped containers list no bindings, which is correct: a stopped container binds
     * nothing at the kernel.
     */
    public static @NonNull List<PublishedPort> publishedPorts(@NonNull List<Object> containers) {
        List<PublishedPort> published = new ArrayList<>();
        for (Object entry : containers) {
            if (!(entry instanceof Map<?, ?> summary)
                || !(summary.get("Ports") instanceof List<?> ports)) {
                continue;
            }
            for (Object binding : ports) {
                if (binding instanceof Map<?, ?> b && b.get("PublicPort") instanceof Number port) {
                    String address = b.get("IP") != null ? String.valueOf(b.get("IP")) : "";
                    if (address.equals("0.0.0.0")) {
                        address = "";   // the ledger's whole-host canonicalisation
                    }
                    String protocol = b.get("Type") != null
                        ? String.valueOf(b.get("Type")) : "tcp";
                    published.add(new PublishedPort(address, port.intValue(), protocol));
                }
            }
        }
        return published;
    }

    /** Whether a claim tuple overlaps any published binding (the kernel-exclusivity rule:
     *  a whole-host bind and a specific bind of one port exclude each other). */
    public static boolean stillPublished(@NonNull String address, int port,
                                         @NonNull String protocol,
                                         @NonNull List<PublishedPort> published) {
        for (PublishedPort binding : published) {
            if (binding.port() == port && binding.protocol().equals(protocol)
                && (address.isEmpty() || binding.address().isEmpty()
                    || binding.address().equals(address))) {
                return true;
            }
        }
        return false;
    }

    /**
     * THE observer the reserved-until-observed contract waits for: delete each of this
     * server's {@code releasing} claims whose port the sweep OBSERVED free, keep the
     * rest. Evidence per host kind: the daemon's own port bindings always count; on the
     * LOCAL host an OS bind probe must ALSO pass, because managed processes and other
     * non-docker binders are invisible to the daemon. On a REMOTE host the daemon is the
     * only honest witness we have -- and every remote claim is docker-born (managed
     * processes are local-only), so daemon evidence is authoritative there.
     */
    public static @NonNull ReleasingSweep sweepReleasingClaims(@NonNull String serverName,
                                                               @NonNull List<Object> containers,
                                                               @NonNull Probe probe) {
        Row server = Models.get(ServerModel.class).findByName(serverName);
        if (server == null) {
            return new ReleasingSweep(0, 0);
        }
        Integer serverId = server.get(ServerModel.ID);
        boolean local = ServerModel.MODE_LOCAL.equals(server.get(ServerModel.MODE));
        List<PublishedPort> published = publishedPorts(containers);
        int released = 0;
        int retained = 0;
        for (Row claim : PortLedger.releasingClaimsOf(serverId)) {
            String address = String.valueOf(claim.get(PortAllocationModel.HOST_IP));
            Integer port = claim.get(PortAllocationModel.PORT);
            String protocol = String.valueOf(claim.get(PortAllocationModel.PROTOCOL));
            if (port == null) {
                continue;
            }
            boolean bound = stillPublished(address, port, protocol, published)
                || (local && !probe.isFree(address, port, protocol));
            if (bound) {
                retained++;
            } else {
                PortLedger.releaseObserved(claim);
                released++;
            }
        }
        if (released > 0 || retained > 0) {
            Blast.log("DOCKER RECONCILE:", serverName, "- released", released,
                "observed-free releasing port claim(s), kept", retained, "still-bound one(s)");
        }
        return new ReleasingSweep(released, retained);
    }

    /** Replace one server's stored findings with this sweep's result. */
    public static void store(@NonNull String serverName, @NonNull List<Finding> findings) {
        ReconcileFindingModel model = Models.get(ReconcileFindingModel.class);
        model.find().where(ReconcileFindingModel.SERVER_NAME.eq(serverName)).delete();
        for (Finding finding : findings) {
            Row row = model.createEmptyRow();
            row.set(ReconcileFindingModel.SERVER_NAME, serverName);
            row.set(ReconcileFindingModel.KIND, finding.kind());
            row.set(ReconcileFindingModel.RESOURCE_NAME, finding.name());
            row.set(ReconcileFindingModel.BUCKET, finding.bucket().token);
            row.set(ReconcileFindingModel.EVIDENCE,
                finding.evidence().name().toLowerCase(Locale.ROOT));
            if (finding.owner() != null) {
                row.set(ReconcileFindingModel.OWNER_MODEL, finding.owner().model().toString());
                row.set(ReconcileFindingModel.OWNER_ID, finding.owner().id());
            }
            row.set(ReconcileFindingModel.DETAIL, finding.detail());
            model.save(row);
        }
    }

    // -- the attention projection ---------------------------------------------

    /** How many resource names an attention detail line spells out before eliding. */
    private static final int ATTENTION_NAME_CAP = 3;

    /**
     * The stored findings that deserve dashboard attention: per server one warning
     * for orphaned resources (attributed to us, record gone -- volumes here are
     * unreclaimed data) and one for name collisions (a same-named foreign resource
     * is what the legacy replace paths would destroy). Foreign-known and owned rows
     * never surface.
     */
    public static @NonNull List<AttentionItem> attentionItems() {
        List<AttentionItem> items = new ArrayList<>();
        appendBucketItems(items, ReconcileFindingModel.BUCKET_ORPHANED, "docker_orphans");
        appendBucketItems(items, ReconcileFindingModel.BUCKET_FOREIGN_COLLIDING, "docker_colliding");
        return items;
    }

    private static void appendBucketItems(List<AttentionItem> items, String bucket, String key) {
        Map<String, List<String>> namesByServer = new LinkedHashMap<>();
        List<Row> rows = Models.get(ReconcileFindingModel.class).find()
            .where(ReconcileFindingModel.BUCKET.eq(bucket))
            .all();
        for (Row row : rows) {
            namesByServer
                .computeIfAbsent(row.get(ReconcileFindingModel.SERVER_NAME), k -> new ArrayList<>())
                .add(row.get(ReconcileFindingModel.KIND) + " " + row.get(ReconcileFindingModel.RESOURCE_NAME));
        }
        namesByServer.forEach((server, names) -> {
            String listed = String.join(", ", names.subList(0, Math.min(names.size(), ATTENTION_NAME_CAP)))
                + (names.size() > ATTENTION_NAME_CAP ? ", ..." : "");
            Microcopy title = Microcopy.of(key)
                .withFilter("scope", "attention_title")
                .withArg("server", server);
            Microcopy detail = Microcopy.of(key)
                .withFilter("scope", "attention_detail")
                .withArg("count", names.size())
                .withArg("names", listed);
            items.add(new AttentionItem("warning", "cubes", title, detail, ""));
        });
    }
}
