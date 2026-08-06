package be.elevenways.hohenheim.server.game;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.LinkNetworkSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * THE write funnel for game-domain mappings, and their materializer: generated Velocity
 * forced-hosts config (an attributed {@code instance_files} row, pushed into a present
 * container), a generated SRV row in the DNS tier, the proxy-to-backend link network and
 * the forwarding-secret hand-off -- all reconciled on every change.
 *
 * SECURITY CORE: creating or changing a mapping requires authority over BOTH records --
 * manage on the domain's parent site AND manage on both instances -- so a tenant who
 * controls an instance cannot aim someone else's domain at it, and a tenant who controls
 * a domain cannot aim it at someone else's instance. Enforced HERE, the one funnel every
 * surface routes through, never in a resource.
 *
 * AIDEV-NOTE: control-plane rows (mapping, generated file, DNS) are the source of truth
 * and always materialize; DAEMON-side convergence (link network, file push) happens
 * immediately when the containers are present, and otherwise at deploy -- which REFUSES
 * when the policy cannot be enforced, so deferral is never a bypass. An UNREACHABLE
 * daemon defers the same way, loudly logged.
 */
public final class GameDomains {

    /** {@code generated_by} token for everything this materializer writes. */
    public static final String SOURCE = GeneratedDnsRecords.SOURCE_GAME_DOMAIN;

    /** The proxy-side secret variable a Velocity template declares (auto-generated). */
    public static final String PROXY_SECRET_KEY = "FORWARDING_SECRET";

    /** The backend-side secret variable this materializer fills from the proxy's. */
    public static final String BACKEND_SECRET_KEY = "VELOCITY_FORWARDING_SECRET";

    /** SRV service prefix; Minecraft clients look up {@code _minecraft._tcp.<host>}. */
    static final String SRV_PREFIX = "_minecraft._tcp";

    private static volatile boolean installed;

    private GameDomains() {
    }

    /** Link-network handle of one proxy/backend pair (shared by all their mappings). */
    public static @NonNull String linkHandle(int proxyId, int backendId) {
        return ControllerScope.handle(ControllerScope.KIND_GAMELINK, proxyId + "-" + backendId);
    }

    /**
     * Per-server link-network handles of every ENABLED mapping whose proxy instance is
     * live: the isolation sweep's inventory of game-domain link networks whose kernel
     * chains must exist (a host reboot erases them while the containers restart).
     */
    public static @NonNull Map<Integer, List<String>> liveLinkHandles() {
        Map<Integer, List<String>> byServer = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        InstanceModel instances = Models.get(InstanceModel.class);
        for (Row mapping : Models.get(GameDomainModel.class).find()
                .where(GameDomainModel.ENABLED.eq(true)).all()) {
            Integer proxyId = mapping.get(GameDomainModel.PROXY_INSTANCE_ID);
            Integer backendId = mapping.get(GameDomainModel.BACKEND_INSTANCE_ID);
            if (proxyId == null || backendId == null) {
                continue;
            }
            String handle = linkHandle(proxyId, backendId);
            if (!seen.add(handle)) {
                continue;
            }
            Row proxy = instances.find().where(InstanceModel.ID.eq(proxyId))
                .where(InstanceModel.DELETED_AT.isNull()).first();
            if (proxy == null) {
                continue;
            }
            byServer.computeIfAbsent(
                    ServerModel.canonicalServerId(proxy.get(InstanceModel.SERVER_ID)),
                    key -> new ArrayList<>())
                .add(handle);
        }
        return byServer;
    }

    /**
     * Install the domain-row cascade: a mapping dies with its domain row, taking its
     * generated output along -- the domain delete path never fires ORM remove hooks on
     * the mapping otherwise.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        SiteDomainModel.SCHEMA.addBeforeRemoveHook(context -> {
            Model model = context.getModel();
            if (model == null) {
                return;
            }
            QueryContext queryContext = context.getQueryContext();
            Criteria criteria = queryContext != null ? queryContext.getCriteria() : null;
            QueryBuilder<Row> builder = model.find();
            if (criteria != null) {
                builder.where(criteria);
            }
            for (Row domain : builder.all()) {
                Integer domainId = domain.get(SiteDomainModel.ID);
                if (domainId == null) {
                    continue;
                }
                for (Row mapping : Models.get(GameDomainModel.class)
                        .findBySiteDomainId(domainId)) {
                    deleteMapping(mapping, true);
                }
            }
        });
        // A changed host address re-reconciles every mapping whose proxy runs on that
        // host: a stale A record pointing at an address the host no longer owns is a
        // dangling pointer (the class the release-quarantine ledger exists to prevent).
        // Before/after pairing because only beforeWrite can still see the STORED values;
        // reconciling on every save would bump zone serials on each host probe.
        ServerModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Object id = row.get(ServerModel.ID.getName());
            Row stored = id instanceof Integer serverId
                ? Models.get(ServerModel.class).findById(serverId) : null;
            boolean changed = addressDiffers(row, stored, ServerModel.PUBLIC_IPV4.getName())
                || addressDiffers(row, stored, ServerModel.PUBLIC_IPV6.getName());
            if (changed) {
                context.setAttribute(SERVER_ADDRESS_CHANGED, Boolean.TRUE);
            }
        });
        ServerModel.SCHEMA.addAfterSaveHook(context -> {
            if (!Boolean.TRUE.equals(context.getAttribute(SERVER_ADDRESS_CHANGED))) {
                return;
            }
            Row row = context.getRow();
            Object id = row != null ? row.get(ServerModel.ID.getName()) : null;
            if (id instanceof Integer serverId) {
                afterServerAddressChange(serverId);
            }
        });
    }

    /** Context key the address-change before/after pairing hands its verdict over. */
    private static final String SERVER_ADDRESS_CHANGED = "hohenheim.game.server-address-changed";

    private static boolean addressDiffers(@NonNull Row row, @Nullable Row stored,
                                          @NonNull String field) {
        if (!row.has(field)) {
            return false;
        }
        return !Objects.equals(row.get(field), stored != null ? stored.get(field) : null);
    }

    /**
     * Re-reconcile the generated DNS of every mapping whose PROXY runs on this host --
     * the A/AAAA values are the host's declared addresses, so they move (or come down)
     * with the declaration.
     */
    public static void afterServerAddressChange(int serverId) {
        for (Row mapping : Models.get(GameDomainModel.class).find().all()) {
            Integer proxyId = mapping.get(GameDomainModel.PROXY_INSTANCE_ID);
            if (proxyId == null) {
                continue;
            }
            Row server = proxyServer(proxyId);
            if (server != null && Objects.equals(server.get(ServerModel.ID), serverId)) {
                reconcileDns(mapping);
            }
        }
    }

    // -- the authorized write funnel ------------------------------------------

    /**
     * Validate, authorize and save a new or changed mapping, then materialize its output
     * (and the PREVIOUS proxy's, when the mapping moved).
     *
     * @throws Violations naming the missing authority or the structural refusal
     */
    public static @NonNull Row applyAuthorized(@NonNull AccessContext ctx, @NonNull Row row) {
        GameDomainModel model = Models.get(GameDomainModel.class);
        Integer id = row.get(GameDomainModel.ID);
        Row stored = id != null ? model.findById(id) : null;

        int siteDomainId = requireInt(effective(row, stored, GameDomainModel.SITE_DOMAIN_ID),
            "site_domain_id");
        int backendId = requireInt(effective(row, stored, GameDomainModel.BACKEND_INSTANCE_ID),
            "backend_instance_id");
        int proxyId = requireInt(effective(row, stored, GameDomainModel.PROXY_INSTANCE_ID),
            "proxy_instance_id");

        Row domain = requireDomain(siteDomainId);
        Row backend = requireInstance(backendId, "backend_instance_id");
        Row proxy = requireInstance(proxyId, "proxy_instance_id");

        if (backendId == proxyId) {
            throw Violations.ofField("proxy_instance_id", proxyId,
                CmsSupport.violationText("game_domain_same_instance"));
        }
        // Docker networks are per-host: a proxy cannot reach a backend on another server.
        if (ServerModel.canonicalServerId(backend.get(InstanceModel.SERVER_ID))
                != ServerModel.canonicalServerId(proxy.get(InstanceModel.SERVER_ID))) {
            throw Violations.ofField("backend_instance_id", backendId,
                CmsSupport.violationText("game_domain_cross_host"));
        }
        // Named duplicate refusal before the unique index turns it into an anonymous one.
        Row duplicate = model.find()
            .where(GameDomainModel.SITE_DOMAIN_ID.eq(siteDomainId))
            .and(GameDomainModel.PROXY_INSTANCE_ID.eq(proxyId))
            .first();
        if (duplicate != null && !Objects.equals(duplicate.get(GameDomainModel.ID), id)) {
            throw Violations.ofField("site_domain_id", siteDomainId,
                CmsSupport.violationText("game_domain_duplicate"));
        }

        requireAuthority(ctx, domain, backendId, proxyId);
        if (stored != null) {
            // Changing a side away also needs authority over the side being LEFT: a
            // principal must not detach a mapping from a domain or instance it cannot
            // speak for.
            Integer oldDomainId = stored.get(GameDomainModel.SITE_DOMAIN_ID);
            Integer oldBackend = stored.get(GameDomainModel.BACKEND_INSTANCE_ID);
            Integer oldProxy = stored.get(GameDomainModel.PROXY_INSTANCE_ID);
            if (oldDomainId != null && oldDomainId != siteDomainId) {
                Row oldDomain = Models.get(SiteDomainModel.class).findById(oldDomainId);
                if (oldDomain != null) {
                    requireDomainAuthority(ctx, oldDomain);
                }
            }
            if (oldBackend != null && oldBackend != backendId) {
                requireInstanceAuthority(ctx, oldBackend, "backend_instance_id");
            }
            if (oldProxy != null && oldProxy != proxyId) {
                requireInstanceAuthority(ctx, oldProxy, "proxy_instance_id");
            }
        }

        // A hand-authored file on the generated path is refused BEFORE anything lands:
        // the materializer must never adopt or overwrite what it did not write.
        requireGeneratedPathFree(proxyId);

        Integer oldProxyId = stored != null ? stored.get(GameDomainModel.PROXY_INSTANCE_ID) : null;
        Integer oldBackendId = stored != null
            ? stored.get(GameDomainModel.BACKEND_INSTANCE_ID) : null;

        model.save(row);

        materializeMapping(row);
        if (oldProxyId != null && oldProxyId != proxyId) {
            materializeProxyConfig(oldProxyId, false);
        }
        if (oldProxyId != null && oldBackendId != null
                && (oldProxyId != proxyId || oldBackendId != backendId)) {
            maybeRemoveLink(oldProxyId, oldBackendId);
        }
        return row;
    }

    /**
     * Delete one mapping and its generated output. Any single-side authority suffices:
     * severing a mapping only REDUCES exposure, and each stakeholder must be able to
     * cut a link that involves their record.
     */
    public static void deleteAuthorized(@NonNull AccessContext ctx, int mappingId) {
        GameDomainModel model = Models.get(GameDomainModel.class);
        Row mapping = model.findById(mappingId);
        if (mapping == null) {
            return;
        }
        boolean allowed = HohenheimAccess.isAdmin(ctx);
        if (!allowed) {
            Integer domainId = mapping.get(GameDomainModel.SITE_DOMAIN_ID);
            Row domain = domainId != null
                ? Models.get(SiteDomainModel.class).findById(domainId) : null;
            Integer siteId = domain != null ? domain.get(SiteDomainModel.SITE_ID) : null;
            allowed = (siteId != null && HohenheimAccess.canManageSite(ctx, siteId))
                || canManage(ctx, mapping.get(GameDomainModel.BACKEND_INSTANCE_ID))
                || canManage(ctx, mapping.get(GameDomainModel.PROXY_INSTANCE_ID));
        }
        if (!allowed) {
            throw Violations.ofForm(CmsSupport.violationText("game_domain_authority_none"));
        }
        deleteMapping(mapping, false);
    }

    // -- lifecycle wiring (instance tier) -------------------------------------

    /**
     * Explicit cleanup for {@code InstanceService.destroy}: destroy SOFT-deletes through
     * save(), so remove hooks never fire and nothing else would take the mappings, their
     * DNS rows or the link networks down with the instance.
     */
    public static void deleteForInstance(int instanceId) {
        for (Row mapping : Models.get(GameDomainModel.class).findByInstanceId(instanceId)) {
            deleteMapping(mapping, true);
        }
    }

    /**
     * Attach the instance's link networks between container CREATE and START: deploy
     * recreates the container, which drops every non-primary network attachment.
     *
     * @throws IOException when a link network or attachment cannot be enforced
     * @throws Violations when mappings exist but the driver cannot do link networks
     */
    public static void attachLinksBeforeStart(InstanceService.Resolved resolved,
                                              int instanceId) throws IOException {
        List<Row> mappings = enabledMappingsTouching(instanceId);
        if (mappings.isEmpty()) {
            return;
        }
        LinkNetworkSupport links = requireLinkSupport(resolved, instanceId);
        for (Row mapping : mappings) {
            int proxyId = mapping.get(GameDomainModel.PROXY_INSTANCE_ID);
            int backendId = mapping.get(GameDomainModel.BACKEND_INSTANCE_ID);
            String handle = linkHandle(proxyId, backendId);
            links.ensureLinkNetwork(handle, OwnerLabels.of(InstanceModel.MODEL_ID, backendId),
                Egress.OPEN);
            links.connectToLinkNetwork(handle, resolved.spec().handle());
        }
    }

    /**
     * After a successful deploy: the published proxy port may have changed (loopback
     * publications are ephemeral), so every SRV row riding this proxy re-reconciles.
     */
    public static void afterInstanceDeploy(int instanceId) {
        for (Row mapping : Models.get(GameDomainModel.class).findByProxyId(instanceId)) {
            reconcileDns(mapping);
        }
    }

    // -- materialization ------------------------------------------------------

    /** Full materialization of one mapping: link, secret hand-off, proxy config, DNS. */
    private static void materializeMapping(@NonNull Row mapping) {
        int proxyId = mapping.get(GameDomainModel.PROXY_INSTANCE_ID);
        ensureLink(mapping);
        syncBackendSecret(mapping);
        materializeProxyConfig(proxyId, false);
        reconcileDns(mapping);
    }

    /** System-path delete: generated DNS rows, the row, the proxy re-render, the link. */
    private static void deleteMapping(@NonNull Row mapping, boolean tolerateDaemonFailure) {
        Integer mappingId = mapping.get(GameDomainModel.ID);
        int proxyId = mapping.get(GameDomainModel.PROXY_INSTANCE_ID);
        int backendId = mapping.get(GameDomainModel.BACKEND_INSTANCE_ID);
        if (mappingId != null) {
            removeDnsFor(mappingId);
        }
        Models.get(GameDomainModel.class).delete(mapping);
        try {
            materializeProxyConfig(proxyId, tolerateDaemonFailure);
        } catch (Violations refused) {
            if (!tolerateDaemonFailure) {
                throw refused;
            }
            Blast.log("GAME: proxy", proxyId, "config re-render after mapping delete"
                + " failed:", refused.getMessage());
        }
        maybeRemoveLink(proxyId, backendId);
    }

    /**
     * Re-render the proxy's generated velocity.toml from its enabled mappings; create,
     * update or delete ONLY the attributed row, and push it into a present container.
     */
    static void materializeProxyConfig(int proxyId, boolean tolerateDaemonFailure) {
        List<VelocityConfigs.Entry> entries = new ArrayList<>();
        for (Row mapping : Models.get(GameDomainModel.class).findByProxyId(proxyId)) {
            if (!Boolean.TRUE.equals(mapping.get(GameDomainModel.ENABLED))) {
                continue;
            }
            Row domain = mappingDomain(mapping);
            if (domain == null) {
                continue;
            }
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (hostname == null || hostname.isBlank()) {
                continue;
            }
            int backendId = mapping.get(GameDomainModel.BACKEND_INSTANCE_ID);
            Integer port = mapping.get(GameDomainModel.BACKEND_PORT);
            entries.add(new VelocityConfigs.Entry(hostname, backendId,
                ControllerScope.handle(ControllerScope.KIND_INSTANCE, backendId), port != null ? port : 25565));
        }

        Model files = Models.get(InstanceFileModel.class);
        Row existing = files.find()
            .where(InstanceFileModel.INSTANCE_ID.eq(proxyId))
            .and(InstanceFileModel.CONTAINER_PATH.eq(VelocityConfigs.CONFIG_PATH))
            .first();
        boolean ours = existing != null
            && SOURCE.equals(existing.get(InstanceFileModel.GENERATED_BY));

        try {
            if (entries.isEmpty()) {
                if (ours) {
                    Row doomed = existing;
                    GeneratedRows.sweeping(SOURCE, () -> files.delete(doomed));
                }
            } else {
                if (existing != null && !ours) {
                    // Never adopt a hand-authored row; requireGeneratedPathFree refuses
                    // this at mapping-write time, so reaching it here means the operator
                    // authored the file AFTER mappings existed -- still never overwrite.
                    throw Violations.ofForm(CmsSupport.violationText("game_file_conflict")
                        .withArg("path", VelocityConfigs.CONFIG_PATH));
                }
                String content = VelocityConfigs.render(proxyBindPort(proxyId), entries);
                Row target = existing != null
                    ? existing : files.createEmptyRow();
                if (existing == null) {
                    target.set(InstanceFileModel.INSTANCE_ID, proxyId);
                    target.set(InstanceFileModel.CONTAINER_PATH, VelocityConfigs.CONFIG_PATH);
                    target.set(InstanceFileModel.MODE, "0644");
                }
                if (!content.equals(target.get(InstanceFileModel.CONTENT))) {
                    target.set(InstanceFileModel.CONTENT, content);
                    GeneratedRows.as(new GeneratedRows.Attribution(SOURCE,
                        InstanceModel.MODEL_ID.toString(), proxyId),
                        () -> files.save(target));
                }
            }
        } catch (Violations refused) {
            throw refused;
        } catch (Exception e) {
            throw Violations.ofForm(CmsSupport.violationText("game_materialize_failed")
                .withArg("reason", e.getMessage() != null ? e.getMessage() : e.toString()));
        }

        pushFiles(proxyId, tolerateDaemonFailure);
    }

    /**
     * Push the proxy's (or backend's) config files into its container when one is
     * present, and ask a live Velocity console to reload. ABSENT defers to deploy;
     * UNREACHABLE defers loudly (deploy re-stages unconditionally).
     */
    private static void pushFiles(int instanceId, boolean tolerateDaemonFailure) {
        try {
            new InstanceService().restageConfigFiles(instanceId);
        } catch (IOException | RuntimeException e) {
            if (!tolerateDaemonFailure) {
                throw Violations.ofForm(CmsSupport.violationText("game_push_failed")
                    .withArg("reason", e.getMessage() != null ? e.getMessage() : e.toString()));
            }
            Blast.log("GAME: could not push config files into instance", instanceId,
                "-- they will land at the next deploy:", e.getMessage());
            return;
        }
        try {
            if (InstanceConsoles.peek(instanceId) != null) {
                InstanceConsoles.sendCommand(instanceId, "velocity reload");
            }
        } catch (RuntimeException e) {
            // The file is in place; a failed reload only delays pickup to the next start.
            Blast.log("GAME: velocity reload on instance", instanceId, "failed:",
                e.getMessage());
        }
    }

    /**
     * Hand the proxy's forwarding secret to the backend as an ENCRYPTED instance
     * variable, so the backend's config renders it at stage time and it is never
     * persisted plaintext.
     */
    static void syncBackendSecret(@NonNull Row mapping) {
        int proxyId = mapping.get(GameDomainModel.PROXY_INSTANCE_ID);
        int backendId = mapping.get(GameDomainModel.BACKEND_INSTANCE_ID);
        InstanceVariables variables = new InstanceVariables();
        String secret = variables.valuesFor(proxyId).get(PROXY_SECRET_KEY);
        if (secret == null || secret.isBlank()) {
            return;   // the proxy declares no forwarding secret; nothing to hand off
        }
        if (secret.equals(variables.valuesFor(backendId).get(BACKEND_SECRET_KEY))) {
            return;
        }
        Model model = Models.get(InstanceVariableModel.class);
        Row existing = model.find()
            .where(InstanceVariableModel.INSTANCE_ID.eq(backendId))
            .and(InstanceVariableModel.KEY.eq(BACKEND_SECRET_KEY))
            .first();
        Row row = existing != null ? existing : model.createEmptyRow();
        if (existing == null) {
            row.set(InstanceVariableModel.INSTANCE_ID, backendId);
            row.set(InstanceVariableModel.KEY, BACKEND_SECRET_KEY);
        }
        row.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_SECRET);
        row.set(InstanceVariableModel.PLAIN_VALUE, null);
        row.set(InstanceVariableModel.SECRET_VALUE, secret);
        model.save(row);
        pushFiles(backendId, true);
    }

    /** One desired generated row: type + owner name + the type-specific payload. */
    private record DesiredRecord(@NonNull String type, @NonNull String owner,
                                 @NonNull String value, @Nullable Integer port) {
    }

    /**
     * Reconcile the mapping's generated DNS output: an SRV row aimed at the mapped
     * hostname on the proxy's PUBLIC pre-allocated port, plus an A (and AAAA) row at
     * that hostname carrying the host's DECLARED public address, so the SRV target
     * resolves off our own zone. Updates or removes ONLY rows carrying this mapping's
     * exact attribution; a foreign or hand-authored row is never touched.
     *
     * AIDEV-NOTE: DNS materializes ONLY off a PUBLIC publication. A loopback-published
     * proxy is unreachable from outside by design, so an SRV/A pair pointing at it would
     * be exactly the dangling-pointer shape this wave exists to kill -- the rows come
     * down (or never appear) instead. A rows additionally need the server-address
     * authority (servers.public_ipv4/6); a public port on a host with no declared
     * address generates the SRV only, for operators whose A records live elsewhere.
     */
    static void reconcileDns(@NonNull Row mapping) {
        Integer mappingId = mapping.get(GameDomainModel.ID);
        if (mappingId == null) {
            return;
        }
        Model records = Models.get(DnsRecordModel.class);
        List<Row> owned = ownedDnsRows(mappingId);

        Row desiredZone = null;
        List<DesiredRecord> desired = new ArrayList<>();
        Row domain = mappingDomain(mapping);
        if (Boolean.TRUE.equals(mapping.get(GameDomainModel.ENABLED)) && domain != null) {
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (hostname != null && !hostname.isBlank()) {
                desiredZone = zoneFor(hostname);
                Integer publicPort = publicProxyPort(
                    mapping.get(GameDomainModel.PROXY_INSTANCE_ID));
                if (desiredZone != null && publicPort != null) {
                    String origin = desiredZone.get(DnsZoneModel.ORIGIN);
                    String relative = DnsNames.relative(origin, hostname);
                    String srvOwner = DnsNames.APEX.equals(relative)
                        ? SRV_PREFIX : SRV_PREFIX + "." + relative;
                    desired.add(new DesiredRecord(DnsRecordModel.TYPE_SRV, srvOwner,
                        hostname, publicPort));
                    Row server = proxyServer(mapping.get(GameDomainModel.PROXY_INSTANCE_ID));
                    String v4 = server != null ? server.get(ServerModel.PUBLIC_IPV4) : null;
                    String v6 = server != null ? server.get(ServerModel.PUBLIC_IPV6) : null;
                    if (v4 != null && !v4.isBlank()) {
                        desired.add(new DesiredRecord(DnsRecordModel.TYPE_A, relative,
                            v4, null));
                    }
                    if (v6 != null && !v6.isBlank()) {
                        desired.add(new DesiredRecord(DnsRecordModel.TYPE_AAAA, relative,
                            v6, null));
                    }
                }
            }
        }

        Set<Integer> touchedZones = new LinkedHashSet<>();
        for (Row row : owned) {
            touchedZones.add(row.get(DnsRecordModel.ZONE_ID));
        }
        try {
            if (desiredZone == null || desired.isEmpty()) {
                // Nothing to serve (disabled, no covering zone, proxy not publicly
                // published): generated rows come down rather than dangle.
                GeneratedRows.sweeping(SOURCE, () -> {
                    for (Row row : owned) {
                        records.delete(row);
                    }
                });
            } else {
                int zoneId = desiredZone.get(DnsZoneModel.ID);
                // Pair each desired type with the owned row of that type; leftovers die.
                Map<String, Row> reusable = new LinkedHashMap<>();
                List<Row> extra = new ArrayList<>();
                for (Row row : owned) {
                    String type = row.get(DnsRecordModel.TYPE);
                    if (type != null && reusable.putIfAbsent(type, row) == null) {
                        continue;
                    }
                    extra.add(row);
                }
                List<Row> writes = new ArrayList<>();
                for (DesiredRecord want : desired) {
                    Row target = reusable.remove(want.type());
                    if (target == null) {
                        target = records.createEmptyRow();
                    }
                    target.set(DnsRecordModel.ZONE_ID, zoneId);
                    target.set(DnsRecordModel.NAME, want.owner());
                    target.set(DnsRecordModel.TYPE, want.type());
                    target.set(DnsRecordModel.PRIORITY,
                        DnsRecordModel.TYPE_SRV.equals(want.type()) ? 0 : null);
                    target.set(DnsRecordModel.WEIGHT,
                        DnsRecordModel.TYPE_SRV.equals(want.type()) ? 5 : null);
                    target.set(DnsRecordModel.PORT, want.port());
                    target.set(DnsRecordModel.VALUE, want.value());
                    target.set(DnsRecordModel.ENABLED, true);
                    writes.add(target);
                }
                extra.addAll(reusable.values());
                GeneratedRows.as(new GeneratedRows.Attribution(SOURCE,
                    GameDomainModel.MODEL_ID.toString(), mappingId), () -> {
                        for (Row target : writes) {
                            records.save(target);
                        }
                        for (Row row : extra) {
                            records.delete(row);
                        }
                    });
                touchedZones.add(zoneId);
            }
        } catch (Violations refused) {
            throw refused;
        } catch (Exception e) {
            throw Violations.ofForm(CmsSupport.violationText("game_materialize_failed")
                .withArg("reason", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
        bumpZones(touchedZones);
    }

    /** Remove every DNS row carrying this mapping's attribution. */
    static void removeDnsFor(int mappingId) {
        List<Row> owned = ownedDnsRows(mappingId);
        if (owned.isEmpty()) {
            return;
        }
        Model records = Models.get(DnsRecordModel.class);
        GeneratedRows.sweeping(SOURCE, () -> {
            for (Row row : owned) {
                records.delete(row);
            }
        });
        Set<Integer> zones = new LinkedHashSet<>();
        for (Row row : owned) {
            zones.add(row.get(DnsRecordModel.ZONE_ID));
        }
        bumpZones(zones);
    }

    // -- link networks --------------------------------------------------------

    /** Ensure the pair's link network exists and present containers are attached. */
    private static void ensureLink(@NonNull Row mapping) {
        if (!Boolean.TRUE.equals(mapping.get(GameDomainModel.ENABLED))) {
            return;
        }
        int proxyId = mapping.get(GameDomainModel.PROXY_INSTANCE_ID);
        int backendId = mapping.get(GameDomainModel.BACKEND_INSTANCE_ID);
        InstanceService service = new InstanceService();
        InstanceService.Resolved proxy = service.resolve(proxyId);
        InstanceService.Resolved backend = service.resolve(backendId);
        ContainerState proxyState = proxy.runtime().status(proxy.spec().handle()).state();
        ContainerState backendState = backend.runtime().status(backend.spec().handle()).state();
        if (proxyState == ContainerState.UNREACHABLE || backendState == ContainerState.UNREACHABLE) {
            Blast.log("GAME: daemon unreachable; link network for mapping pair", proxyId,
                "->", backendId, "will be enforced at the next deploy");
            return;
        }
        if (proxyState == ContainerState.ABSENT && backendState == ContainerState.ABSENT) {
            return;   // nothing to link yet; deploy attaches with the policy enforced
        }
        LinkNetworkSupport links = requireLinkSupport(proxy, proxyId);
        String handle = linkHandle(proxyId, backendId);
        try {
            links.ensureLinkNetwork(handle, OwnerLabels.of(InstanceModel.MODEL_ID, backendId),
                Egress.OPEN);
            if (proxyState != ContainerState.ABSENT) {
                links.connectToLinkNetwork(handle, proxy.spec().handle());
            }
            if (backendState != ContainerState.ABSENT) {
                links.connectToLinkNetwork(handle, backend.spec().handle());
            }
        } catch (IOException e) {
            throw Violations.ofForm(CmsSupport.violationText("game_link_failed")
                .withArg("reason", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
        // AIDEV-NOTE: connecting a RUNNING container to another network makes Docker
        // re-allocate its ephemeral published host port (observed live: same PID, new
        // HostPort). The ledger and every SRV row riding it would silently go stale, so
        // the port is re-observed here, after the connects.
        refreshProxyPort(proxy, proxyId);
    }

    /** Re-observe the proxy's published port after a link change and refresh the ledger. */
    private static void refreshProxyPort(InstanceService.Resolved proxy, int proxyId) {
        var status = proxy.runtime().status(proxy.spec().handle());
        Integer fresh = status.publishedPort();
        if (!status.running() || fresh == null) {
            return;
        }
        Integer recorded = publishedProxyPort(proxyId);
        if (Objects.equals(recorded, fresh)) {
            return;
        }
        PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, proxyId);
        PortLedger.recordObserved(proxy.serverId(), "127.0.0.1", fresh, "tcp",
            InstanceModel.MODEL_ID, proxyId, null);
    }

    /** Remove the pair's link network when no enabled mapping needs it any more. */
    private static void maybeRemoveLink(int proxyId, int backendId) {
        GameDomainModel model = Models.get(GameDomainModel.class);
        for (Row mapping : model.findByProxyId(proxyId)) {
            Integer backend = mapping.get(GameDomainModel.BACKEND_INSTANCE_ID);
            if (backend != null && backend == backendId
                    && Boolean.TRUE.equals(mapping.get(GameDomainModel.ENABLED))) {
                return;   // still in use
            }
        }
        // Resolve a runtime through whichever side still exists; both gone means the
        // instances' own destroy path already tore their networks down.
        InstanceService service = new InstanceService();
        InstanceService.Resolved resolved = tryResolve(service, proxyId);
        if (resolved == null) {
            resolved = tryResolve(service, backendId);
        }
        if (resolved == null || !(resolved.runtime() instanceof LinkNetworkSupport links)) {
            return;
        }
        try {
            links.removeLinkNetwork(linkHandle(proxyId, backendId));
        } catch (IOException e) {
            Blast.log("GAME: could not remove link network", linkHandle(proxyId, backendId),
                ":", e.getMessage());
        }
        // A disconnect re-allocates the proxy's ephemeral published port just like a
        // connect does; refresh it and re-reconcile the SRV rows of surviving mappings.
        InstanceService.Resolved proxy = tryResolve(new InstanceService(), proxyId);
        if (proxy != null) {
            refreshProxyPort(proxy, proxyId);
            for (Row survivor : Models.get(GameDomainModel.class).findByProxyId(proxyId)) {
                reconcileDns(survivor);
            }
        }
    }

    // -- authority ------------------------------------------------------------

    private static void requireAuthority(@NonNull AccessContext ctx, @NonNull Row domain,
                                         int backendId, int proxyId) {
        requireDomainAuthority(ctx, domain);
        requireInstanceAuthority(ctx, backendId, "backend_instance_id");
        requireInstanceAuthority(ctx, proxyId, "proxy_instance_id");
    }

    private static void requireDomainAuthority(@NonNull AccessContext ctx, @NonNull Row domain) {
        Integer siteId = domain.get(SiteDomainModel.SITE_ID);
        if (siteId == null || !HohenheimAccess.canManageSite(ctx, siteId)) {
            throw Violations.ofField("site_domain_id", domain.get(SiteDomainModel.ID),
                CmsSupport.violationText("game_domain_authority_domain"));
        }
    }

    private static void requireInstanceAuthority(@NonNull AccessContext ctx, int instanceId,
                                                 @NonNull String fieldName) {
        if (!HohenheimAccess.canManageInstance(ctx, instanceId)) {
            throw Violations.ofField(fieldName, instanceId,
                CmsSupport.violationText("game_domain_authority_instance"));
        }
    }

    private static boolean canManage(@NonNull AccessContext ctx, @Nullable Integer instanceId) {
        return instanceId != null && HohenheimAccess.canManageInstance(ctx, instanceId);
    }

    // -- helpers --------------------------------------------------------------

    private static @NonNull List<Row> ownedDnsRows(int mappingId) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.GENERATED_BY.eq(SOURCE))
            .and(DnsRecordModel.GENERATED_FOR_MODEL.eq(GameDomainModel.MODEL_ID.toString()))
            .and(DnsRecordModel.GENERATED_FOR_ID.eq(mappingId))
            .all();
    }

    private static @NonNull List<Row> enabledMappingsTouching(int instanceId) {
        List<Row> result = new ArrayList<>();
        for (Row mapping : Models.get(GameDomainModel.class).findByInstanceId(instanceId)) {
            if (Boolean.TRUE.equals(mapping.get(GameDomainModel.ENABLED))) {
                result.add(mapping);
            }
        }
        return result;
    }

    private static @NonNull LinkNetworkSupport requireLinkSupport(
            InstanceService.Resolved resolved, int instanceId) {
        if (resolved.runtime() instanceof LinkNetworkSupport links) {
            return links;
        }
        throw Violations.ofForm(CmsSupport.violationText("game_link_unsupported")
            .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME))));
    }

    private static InstanceService.Resolved tryResolve(
            @NonNull InstanceService service, int instanceId) {
        try {
            return service.resolve(instanceId);
        } catch (Violations gone) {
            return null;
        }
    }

    private static @Nullable Row mappingDomain(@NonNull Row mapping) {
        Integer domainId = mapping.get(GameDomainModel.SITE_DOMAIN_ID);
        return domainId != null ? Models.get(SiteDomainModel.class).findById(domainId) : null;
    }

    /** The deepest enabled zone containing the hostname, or null when none is hosted. */
    /** The most specific enabled hosted zone containing {@code hostname}, or null. */
    public static @Nullable Row zoneFor(@NonNull String hostname) {
        Row best = null;
        int bestLength = -1;
        for (Row zone : Models.get(DnsZoneModel.class).findEnabled()) {
            String origin = zone.get(DnsZoneModel.ORIGIN);
            if (origin != null && DnsNames.zoneContains(origin, hostname)
                    && origin.length() > bestLength) {
                best = zone;
                bestLength = origin.length();
            }
        }
        return best;
    }

    /** The proxy's observed published host port, or null when it is not deployed. */
    static @Nullable Integer publishedProxyPort(int proxyId) {
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, proxyId)) {
            if (!PortLedger.isReleasing(claim)) {
                return claim.get(PortAllocationModel.PORT);
            }
        }
        return null;
    }

    /**
     * The proxy's PUBLIC host port -- a held whole-host claim -- or null. THE gate DNS
     * generation rides: a loopback claim never produces DNS rows, because nothing
     * outside this host could connect to what they would point at.
     */
    static @Nullable Integer publicProxyPort(int proxyId) {
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, proxyId)) {
            if (!PortLedger.isReleasing(claim)
                    && "".equals(claim.get(PortAllocationModel.HOST_IP))) {
                return claim.get(PortAllocationModel.PORT);
            }
        }
        return null;
    }

    /** The servers row of the proxy's host, or null when it cannot be resolved. */
    private static @Nullable Row proxyServer(int proxyId) {
        Row proxy = Models.get(InstanceModel.class).findById(proxyId);
        if (proxy == null) {
            return null;
        }
        try {
            int serverId = ServerModel.canonicalServerId(proxy.get(InstanceModel.SERVER_ID));
            return Models.get(ServerModel.class).findById(serverId);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static int proxyBindPort(int proxyId) {
        Row proxy = Models.get(InstanceModel.class).findById(proxyId);
        if (proxy != null && proxy.get(InstanceModel.SETTINGS) instanceof Map<?, ?> settings
                && settings.get("container_port") instanceof Number port
                && port.intValue() > 0) {
            return port.intValue();
        }
        return VelocityConfigs.DEFAULT_BIND_PORT;
    }

    /** Refuse when a NON-generated file already sits on the generated config path. */
    private static void requireGeneratedPathFree(int proxyId) {
        Row existing = Models.get(InstanceFileModel.class).find()
            .where(InstanceFileModel.INSTANCE_ID.eq(proxyId))
            .and(InstanceFileModel.CONTAINER_PATH.eq(VelocityConfigs.CONFIG_PATH))
            .first();
        if (existing != null && !SOURCE.equals(existing.get(InstanceFileModel.GENERATED_BY))) {
            throw Violations.ofField("proxy_instance_id", proxyId,
                CmsSupport.violationText("game_file_conflict")
                    .withArg("path", VelocityConfigs.CONFIG_PATH));
        }
    }

    private static @NonNull Row requireDomain(int siteDomainId) {
        Row domain = Models.get(SiteDomainModel.class).findById(siteDomainId);
        if (domain == null) {
            throw Violations.ofField("site_domain_id", siteDomainId,
                CmsSupport.violationText("game_domain_missing"));
        }
        Object matchType = domain.get(SiteDomainModel.MATCH_TYPE);
        if (matchType != null && !SiteDomainModel.MATCH_EXACT.equals(matchType)) {
            throw Violations.ofField("site_domain_id", siteDomainId,
                CmsSupport.violationText("game_domain_match_type"));
        }
        return domain;
    }

    private static @NonNull Row requireInstance(int instanceId, @NonNull String fieldName) {
        Row instance = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .first();
        if (instance == null) {
            throw Violations.ofField(fieldName, instanceId,
                CmsSupport.violationText("game_instance_missing"));
        }
        return instance;
    }

    private static @Nullable Object effective(@NonNull Row row, @Nullable Row stored,
                                              @NonNull Field<?, ?> field) {
        if (row.has(field.getName())) {
            return row.get(field.getName());
        }
        return stored != null ? stored.get(field.getName()) : null;
    }

    private static int requireInt(@Nullable Object value, @NonNull String fieldName) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw Violations.ofField(fieldName, value,
            CmsSupport.violationText("game_domain_field_required"));
    }

    private static void bumpZones(@NonNull Set<Integer> zoneIds) {
        for (Integer zoneId : zoneIds) {
            if (zoneId != null) {
                DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
            }
        }
    }
}
