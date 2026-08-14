package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.hohenheim.server.instance.InstanceImagePolicy;
import be.elevenways.hohenheim.server.sitetype.types.ProxySiteType;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.UrlPolicy;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a DELEGATED TENANT may write, enforced on the model write pipelines rather than on
 * any resource, form or handler.
 *
 * A write is tenant-originated when a request is in flight and its principal does NOT hold
 * the installation-wide admin permission. Everything else -- background tasks, seeds, the
 * ACME publisher, CLI tools -- runs with no ambient conduit and is system work.
 *
 * AIDEV-NOTE: the resource layer is NOT an option for any of this, for the reason
 * SiteDomainResource.installRouteInvariant spells out at length: the framework's generic
 * revision-restore endpoint, the peer API, the zone-file import and any direct model.save
 * all reach the datasource without passing a single resource method. A form that omits a
 * field is a UX affordance, never a gate -- a direct POST carries whatever it likes.
 *
 * AIDEV-NOTE: the domain rule is a WHITELIST (see {@link #DOMAIN_TENANT_WRITABLE}), not a
 * blacklist of the four fields the decision named. A blacklist silently opens every column
 * added later; the whitelist makes a new column tenant-frozen by default and forces whoever
 * adds it to decide.
 *
 * AIDEV-NOTE: enforcement here deliberately does NOT touch the read path of
 * {@code DnsRecordModel}. {@code /nic/update} (DYNDNS_UPDATE, public, token-authenticated)
 * resolves its record with a DIRECT model query by token digest and no principal at all --
 * adding a Schema.addBeforeFindHook scope to DnsRecordModel would 404 every router polling
 * in production while looking like a security improvement. That request DOES pass here on
 * the WRITE side (an anonymous conduit reads as tenant-originated) and is allowed because it
 * is bounded to VALUE on a stored DYNAMIC row inside the type allow-list. Do not "fix" the
 * read side; the tenant READ scope lives on the RecordSource (ManagePanel.dnsRecordScope).
 *
 * @author Jelle De Loecker
 */
public final class TenantWrites {

    /**
     * The only columns a delegated tenant may set on a site domain. Everything else must
     * keep the value it already has (or, on a create, its declared default).
     */
    private static final Set<String> DOMAIN_TENANT_WRITABLE = Set.of(
        SiteDomainModel.SITE_ID.getName(),
        SiteDomainModel.HOSTNAME.getName(),
        SiteDomainModel.FORCE_SSL.getName(),
        SiteDomainModel.HSTS_ENABLED.getName(),
        SiteDomainModel.HSTS_SUBDOMAINS.getName(),
        SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName());

    /**
     * Columns the write pipeline DERIVES; comparing them against the stored row would refuse
     * ordinary saves rather than protect anything. The primary key identifies the row, the
     * timestamps are behaviour-stamped, and live_route_key is written by the route invariant
     * on a later tier of this very pass.
     */
    private static final Set<String> DOMAIN_DERIVED = Set.of(
        SiteDomainModel.ID.getName(),
        SiteDomainModel.CREATED_AT.getName(),
        SiteDomainModel.UPDATED_AT.getName(),
        SiteDomainModel.LIVE_ROUTE_KEY.getName());

    /**
     * The DNS record types a delegated tenant may author. NS/CAA/DS/DNSKEY/MX are absent by
     * DECISION, not by omission: NS delegates a subtree away, CAA disables or redirects
     * issuance for the whole name, and DS/DNSKEY are the DNSSEC trust root.
     */
    public static final Set<String> RECORD_TYPES = Set.of(
        DnsRecordModel.TYPE_A, DnsRecordModel.TYPE_AAAA, DnsRecordModel.TYPE_CNAME,
        DnsRecordModel.TYPE_TXT, DnsRecordModel.TYPE_SRV);

    /** The DnsRecordModel counterpart of {@link #DOMAIN_DERIVED}: pipeline-written columns. */
    private static final Set<String> RECORD_DERIVED = Set.of(
        DnsRecordModel.ID.getName(),
        DnsRecordModel.CREATED_AT.getName(),
        DnsRecordModel.UPDATED_AT.getName());

    private static volatile boolean installed;

    private TenantWrites() {
    }

    /**
     * @return the access context of the request in flight, or null outside a request
     */
    public static @Nullable AccessContext acting() {
        Conduit conduit = RouteScope.currentConduit();
        return conduit != null ? AccessContext.of(conduit) : null;
    }

    /**
     * Whether the write in flight belongs to a delegated tenant rather than to an operator
     * or to the system.
     *
     * AIDEV-NOTE: an ANONYMOUS request reads as tenant-originated on purpose (fail closed) --
     * that is what puts /nic/update under the DNS type allow-list instead of outside it.
     */
    public static boolean isTenantOriginated() {
        if (GeneratedDnsRecords.inSystemScope() || AUTHORIZED_OPERATION.get() > 0) {
            return false;
        }
        AccessContext ctx = acting();
        return ctx != null && !HohenheimAccess.isAdmin(ctx);
    }

    /** Nesting depth of {@link #inAuthorizedOperation} on this thread. */
    private static final ThreadLocal<Integer> AUTHORIZED_OPERATION = ThreadLocal.withInitial(() -> 0);

    /**
     * Run the CONTINUATION of an operation whose authority was already decided at its
     * entry, so its internal steps are not re-asked as if the tenant had requested them
     * separately. A backup stops and redeploys the workload as part of capturing it: the
     * caller was checked for {@code backups} once, and making the internal stop demand
     * {@code manage} as well would mean a backups-only holder could back up a stopped
     * instance and not a running one -- an operation that does less than it claims.
     *
     * AIDEV-NOTE: this is a BYPASS primitive and the ONLY legitimate use is a nested step
     * of an operation that already ran its own capability gate on the SAME record. It is
     * deliberately a depth counter and deliberately restores in a finally: an unbalanced
     * scope would leave the whole thread authorized. Never wrap a request handler in it.
     */
    public static void inAuthorizedOperation(@NonNull Runnable body) {
        AUTHORIZED_OPERATION.set(AUTHORIZED_OPERATION.get() + 1);
        try {
            body.run();
        } finally {
            int depth = AUTHORIZED_OPERATION.get() - 1;
            if (depth <= 0) {
                AUTHORIZED_OPERATION.remove();
            } else {
                AUTHORIZED_OPERATION.set(depth);
            }
        }
    }

    /** Install the tenant-write invariants; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        SiteDomainModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && isTenantOriginated()) {
                checkDomainWrite(row);
            }
        });
        DnsRecordModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && isTenantOriginated()) {
                checkRecordWrite(row);
            }
        });
        SiteModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null) {
                checkProxyUpstream(row, isTenantOriginated());
            }
        });
        InstanceModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && isTenantOriginated()) {
                checkInstanceWrite(row);
            }
        });
        InstanceVariableModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && isTenantOriginated()) {
                requireVariableConfig(instanceOwnerOf(row));
            }
        });
        InstanceVariableModel.SCHEMA.addBeforeRemoveHook(context -> {
            if (!isTenantOriginated()) {
                return;
            }
            for (Row doomed : doomedRows(context)) {
                requireVariableConfig(doomed.get(InstanceVariableModel.INSTANCE_ID));
            }
        });
        DatabaseModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && isTenantOriginated()) {
                checkDatabaseWrite(row);
            }
        });
        DatabaseModel.SCHEMA.addBeforeRemoveHook(context -> {
            if (!isTenantOriginated()) {
                return;
            }
            for (Row doomed : doomedRows(context)) {
                Object id = doomed.get(DatabaseModel.ID);
                if (id == null) {
                    throw HohenheimAccess.databaseRefusal();
                }
                HohenheimAccess.requireDatabaseCapability(
                    (Integer) id, HohenheimAccess.DESTROY);
            }
        });
        SiteDatabaseModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !isTenantOriginated()) {
                return;
            }
            Row stored = row.has(SiteDatabaseModel.ID.getName())
                ? Models.get(SiteDatabaseModel.class).findById(row.get(SiteDatabaseModel.ID))
                : null;
            requireLinkAuthority(effective(row, stored, SiteDatabaseModel.SITE_ID),
                effective(row, stored, SiteDatabaseModel.DATABASE_ID));
            if (stored != null) {
                // Moving a link off a side needs authority over the side being LEFT too,
                // or "re-point my link at your database" launders into a detach.
                requireLinkAuthority(stored.get(SiteDatabaseModel.SITE_ID),
                    stored.get(SiteDatabaseModel.DATABASE_ID));
            }
        });
        SiteDatabaseModel.SCHEMA.addBeforeRemoveHook(context -> {
            if (!isTenantOriginated()) {
                return;
            }
            for (Row doomed : doomedRows(context)) {
                requireLinkAuthority(doomed.get(SiteDatabaseModel.SITE_ID),
                    doomed.get(SiteDatabaseModel.DATABASE_ID));
            }
        });
        InstanceDatabaseModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !isTenantOriginated()) {
                return;
            }
            Row stored = row.has(InstanceDatabaseModel.ID.getName())
                ? Models.get(InstanceDatabaseModel.class).findById(row.get(InstanceDatabaseModel.ID))
                : null;
            requireInstanceLinkAuthority(
                effective(row, stored, InstanceDatabaseModel.INSTANCE_ID),
                effective(row, stored, InstanceDatabaseModel.DATABASE_ID));
            if (stored != null) {
                // Moving a link off a side needs authority over the side being LEFT too,
                // or "re-point my link at your database" launders into a detach.
                requireInstanceLinkAuthority(stored.get(InstanceDatabaseModel.INSTANCE_ID),
                    stored.get(InstanceDatabaseModel.DATABASE_ID));
            }
        });
        InstanceDatabaseModel.SCHEMA.addBeforeRemoveHook(context -> {
            if (!isTenantOriginated()) {
                return;
            }
            for (Row doomed : doomedRows(context)) {
                requireInstanceLinkAuthority(doomed.get(InstanceDatabaseModel.INSTANCE_ID),
                    doomed.get(InstanceDatabaseModel.DATABASE_ID));
            }
        });
        DnsRecordModel.SCHEMA.addBeforeRemoveHook(context -> {
            if (!isTenantOriginated()) {
                return;
            }
            AccessContext ctx = acting();
            HostnameAuthority.Snapshot snapshot = HostnameAuthority.Snapshot.load();
            for (Row doomed : doomedRows(context)) {
                refuseForeignRecordType(doomed.get(DnsRecordModel.TYPE));
                // Removing a row is authority over the row, so it asks the SAME question a
                // write does -- minus the claim half, since a delete claims no new name. An
                // anonymous caller holds none of it: /nic/update updates, it never deletes.
                boolean authorized = ctx != null && !ctx.isAnonymous()
                    && (ctx.hasCapability(DnsRecordModel.MODEL_ID, doomed.get(DnsRecordModel.ID),
                            HohenheimAccess.EDIT)
                        || HostnameAuthority.canManage(snapshot, ctx, fqdnOf(doomed, doomed)));
                if (!authorized) {
                    throw refusal(DnsRecordModel.NAME.getName(), doomed.get(DnsRecordModel.NAME));
                }
            }
        });
        // The dyndns CREDENTIAL is grant-gated on EVERY lane, hostname authority included:
        // the minted token is a bearer credential that survives grant revocation and
        // hostname release (DYNDNS is declared non-delegable for exactly that reason), so
        // a tenant that merely serves the name may never arm one. A dyndns grant is
        // authority over the credential and NOTHING else -- the record itself stays under
        // the ordinary record rules above.
        DnsDyndnsCredentialModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && isTenantOriginated()) {
                checkCredentialWrite(row.get(DnsDyndnsCredentialModel.RECORD_ID));
            }
        });
        DnsDyndnsCredentialModel.SCHEMA.addBeforeRemoveHook(context -> {
            if (!isTenantOriginated()) {
                return;
            }
            // AIDEV-NOTE: DnsClaimReleases deletes credentials inside
            // inAuthorizedOperation (the release is a mandated consequence of a
            // domain delete that already passed its own gate), which makes the
            // write non-tenant-originated and never reaches here.
            for (Row doomed : doomedRows(context)) {
                checkCredentialWrite(doomed.get(DnsDyndnsCredentialModel.RECORD_ID));
            }
        });
    }

    // --- Site domains ----------------------------------------------------------------

    /**
     * Refuse a tenant domain write that reaches past the delegated field set.
     *
     * @throws Violations anchored on the offending field
     */
    private static void checkDomainWrite(@NonNull Row row) {
        Model model = Models.get(SiteDomainModel.class);
        Row stored = row.has(SiteDomainModel.ID.getName())
            ? model.findById(row.get(SiteDomainModel.ID)) : null;

        // The site the row will belong to must be one the tenant actually manages. The
        // resource's AccessFunction scopes what a tenant may READ; it says nothing about
        // the site_id a CREATE submits, and a MOVE to another site is the same question.
        Object siteIdValue = effective(row, stored, SiteDomainModel.SITE_ID);
        AccessContext ctx = acting();
        if (!(siteIdValue instanceof Integer siteId) || ctx == null
                || !HohenheimAccess.canManageSite(ctx, siteId)) {
            throw Violations.ofField(SiteDomainModel.SITE_ID.getName(), siteIdValue,
                CmsSupport.violationText("tenant_site_not_managed"));
        }

        // An unbounded hostname set is not a delegable claim: a tenant wildcard swallows
        // every name under it and a regex can spell anything at all.
        //
        // AIDEV-NOTE: this refusal is LOAD-BEARING for the released-claim quarantine's one
        // stated residue. HostnamePatterns.intersect decides regex-versus-concrete-hostname
        // exactly, but regex-versus-GLOB is undecidable there and answers false -- so a
        // released WILDCARD space can still be re-entered by a regex row. That residue is
        // acceptable only because BOTH sides then have to be operator-authored, which is
        // what this line guarantees. Widening the tenant match-type set reopens it.
        //
        // AIDEV-NOTE: judged on the EFFECTIVE tier, never on the match_type COLUMN. Testing
        // the column alone was a refusal that did less than it claimed: a tenant submitting
        // hostname=*.victim.test with match_type=exact passed it, the row stored exactly as
        // submitted, and SiteDispatcher then routed it in the WILDCARD tier (it reads the
        // hostname's shape) while HostnameAuthority.covers read it as the sole covering row
        // for every name under victim.test -- which requireRecordAuthority below turns into
        // permission to write DNS inside the victim's zone. Neither the conflict scan nor
        // the quarantine caught it, because "*." is one-or-more labels and so does not
        // intersect the apex the victim actually holds.
        Object matchType = effective(row, stored, SiteDomainModel.MATCH_TYPE);
        Object hostnameValue = effective(row, stored, SiteDomainModel.HOSTNAME);
        String tier = SiteDomainModel.effectiveMatchType(
            hostnameValue != null ? String.valueOf(hostnameValue) : null,
            matchType != null ? String.valueOf(matchType) : null);
        if (!SiteDomainModel.MATCH_EXACT.equals(tier)) {
            throw Violations.ofField(SiteDomainModel.MATCH_TYPE.getName(), matchType,
                CmsSupport.violationText("tenant_match_type_exact"));
        }

        // A listener restriction makes the row DISJOINT from every other row's listener set,
        // and refuseRouteConflicts exempts disjoint sets by design -- so a tenant listener
        // walks straight past the hostname-overlap refusal.
        if (isPresent(effective(row, stored, SiteDomainModel.LISTEN_ON))) {
            throw Violations.ofField(SiteDomainModel.LISTEN_ON.getName(),
                effective(row, stored, SiteDomainModel.LISTEN_ON),
                CmsSupport.violationText("tenant_listen_on_frozen"));
        }

        // The SAME exemption applies to a differing path, one dimension over: two rows with
        // different paths are treated as distinct routes and never compared for hostname
        // overlap, so a tenant path carve-out on another tenant's live hostname would be
        // accepted. Freezing PATH empty for tenants is what keeps every tenant-vs-tenant
        // pair comparable. Admin-authored path carve-outs are unaffected (and are the
        // legitimate case the exemption exists for).
        if (isPresent(effective(row, stored, SiteDomainModel.PATH))) {
            throw Violations.ofField(SiteDomainModel.PATH.getName(),
                effective(row, stored, SiteDomainModel.PATH),
                CmsSupport.violationText("tenant_path_frozen"));
        }

        // Everything outside the delegated set keeps its stored value (its default on a
        // create). certificate_id lands here: pinning a certificate row is authority over a
        // name the tenant may not hold, and the picker failing closed is incidental, not a
        // gate.
        for (Field<?, ?> field : model.getSchema().getFields().values()) {
            String name = field.getName();
            if (DOMAIN_TENANT_WRITABLE.contains(name) || DOMAIN_DERIVED.contains(name)
                    || !row.has(name)) {
                continue;
            }
            Object baseline = stored != null ? stored.get(name) : field.getDefaultValue();
            if (!Objects.equals(row.get(name), baseline)) {
                throw Violations.ofField(name, row.get(name),
                    CmsSupport.violationText("tenant_field_frozen"));
            }
        }
    }

    // --- Proxy upstream (SSRF) -------------------------------------------------------

    /**
     * Every writer's baseline: the upstream must be an http(s) host with no embedded
     * credentials. It deliberately does NOT block loopback/private -- proxying to a LAN
     * box or a loopback backend is THE reverse-proxy use case an operator ships.
     */
    private static final UrlPolicy PROXY_UPSTREAM_BASE = UrlPolicy.builder()
        .schemes("http", "https").build();

    /**
     * The delegated-tenant tier additionally refuses loopback, RFC-1918/ULA and link-local
     * literals -- {@code 169.254.169.254} (cloud metadata) and a loopback admin port are the
     * SSRF a tenant with {@code manage} on a proxy site would otherwise reach. An operator
     * pointing a site at a LAN address stays legitimate (the DNS/self-hosted-GitLab stance).
     */
    private static final UrlPolicy PROXY_UPSTREAM_TENANT = UrlPolicy.builder()
        .schemes("http", "https").blockLoopbackHosts().blockPrivateHosts().build();

    /**
     * Refuse a proxy site whose {@code forward_host} the writer may not aim there.
     *
     * AIDEV-NOTE: lives on the model write pipeline, not on a form or handler, for the reason
     * the class docblock states -- the revision-restore endpoint, the peer API and any direct
     * {@code model.save} reach the datasource past every form. The tenant tier reuses the
     * SAME {@link UrlPolicy} mechanism GitProviders applies one tier over; textual host
     * blocking is all it can do here (a DNS name resolving to a private address is caught at
     * fetch time, which is out of scope for a stored setting).
     *
     * @throws Violations {@code proxy_upstream_invalid} (any writer) or
     *         {@code tenant_proxy_upstream_private} (a tenant aiming at a blocked host)
     */
    private static void checkProxyUpstream(@NonNull Row row, boolean tenant) {
        if (!row.has(SiteModel.SETTINGS.getName())) {
            return;
        }
        if (!ProxySiteType.ID.toString().equals(String.valueOf(effectiveSiteType(row)))) {
            return;
        }
        Object settingsValue = row.get(SiteModel.SETTINGS);
        if (!(settingsValue instanceof Map<?, ?> settings)) {
            return;
        }
        Object hostValue = settings.get(ProxySiteType.FORWARD_HOST.getName());
        if (hostValue == null || String.valueOf(hostValue).isBlank()) {
            return;
        }
        String host = String.valueOf(hostValue).trim();
        Object schemeValue = settings.get(ProxySiteType.FORWARD_SCHEME.getName());
        String scheme = schemeValue != null && !String.valueOf(schemeValue).isBlank()
            ? String.valueOf(schemeValue) : "http";
        String url = scheme + "://" + host;

        if (tenant) {
            if (PROXY_UPSTREAM_TENANT.problemOf(url) != null) {
                throw Violations.ofField(
                    SiteModel.SETTINGS.getName() + "." + ProxySiteType.FORWARD_HOST.getName(),
                    host, CmsSupport.violationText("tenant_proxy_upstream_private"));
            }
            return;
        }
        if (PROXY_UPSTREAM_BASE.problemOf(url) != null) {
            throw Violations.ofField(
                SiteModel.SETTINGS.getName() + "." + ProxySiteType.FORWARD_HOST.getName(),
                host, CmsSupport.violationText("proxy_upstream_invalid"));
        }
    }

    /** The site type the write ends up with, reading the stored row on a partial update. */
    private static @Nullable Object effectiveSiteType(@NonNull Row row) {
        if (row.has(SiteModel.SITE_TYPE.getName())) {
            return row.get(SiteModel.SITE_TYPE);
        }
        Object id = row.has(SiteModel.ID.getName()) ? row.get(SiteModel.ID) : null;
        if (id == null) {
            return null;
        }
        Row stored = Models.get(SiteModel.class).findById(id);
        return stored != null ? stored.get(SiteModel.SITE_TYPE) : null;
    }

    // --- Instances -------------------------------------------------------------------

    /**
     * The only columns a delegated tenant may author on an instance: exactly what
     * {@code ManageInstanceResource}'s form offers. Kind, settings (image, command,
     * environment), the host pick and every lifecycle column are execution and placement
     * decisions -- authoring one is authoring what runs and where.
     */
    private static final Set<String> INSTANCE_TENANT_WRITABLE = Set.of(
        InstanceModel.NAME.getName(),
        InstanceModel.CRASH_POLICY.getName());

    /**
     * Columns the write pipeline DERIVES, plus the one column that has its OWN
     * capability-aware gate.
     *
     * AIDEV-NOTE: {@code settings} is not frozen as a COLUMN here. It carries the
     * image facts, and {@link be.elevenways.hohenheim.server.instance.InstanceImagePolicy}
     * is the purpose-built gate over exactly those -- including the sanctioned
     * {@code image_any} override on the record. Freezing the column outright made
     * that shipped capability unreachable and replaced a precise refusal
     * ({@code image_requires_capability}) with a blunt one, which is a gate doing
     * LESS than the one it shadowed. A tenant reaching this column at all still had
     * to pass the {@code config} check above.
     *
     * AIDEV-NOTE: what the column-level exemption used to mean, wrongly, was that the
     * OTHER settings members had no gate at all -- the image policy judges image, tag and
     * image_origin and nothing else, so a direct tenant POST could move
     * {@code settings.privileged}, which IncusContainerKind lowers straight onto an Incus
     * {@code security.privileged} container (threat-model boundary 1). The CMS form not
     * offering the field is a UX affordance, never a gate, which is this whole class's
     * premise. {@link #checkInstanceSettingsWrite} is therefore the per-KEY twin of the
     * frozen-column rule: everything outside
     * {@code InstanceImagePolicy.JUDGED_SETTINGS_KEYS} is frozen exactly like a frozen
     * column, and the image keys stay the image policy's business alone.
     */
    private static final Set<String> INSTANCE_DERIVED = Set.of(
        InstanceModel.ID.getName(),
        InstanceModel.CREATED_AT.getName(),
        InstanceModel.UPDATED_AT.getName(),
        InstanceModel.SETTINGS.getName());

    /**
     * Refuse a tenant instance UPDATE that either lacks {@code config} on the record or
     * reaches past the delegated column set.
     *
     * AIDEV-NOTE: this lives on the write pipeline for the reason the class docblock
     * states -- a form that omits a field is a UX affordance, never a gate, and a direct
     * POST carries whatever it likes. It is the enforcing half of the Phase 3 gate clause
     * "a console+power delegate PROVABLY cannot change config".
     *
     * AIDEV-NOTE: CREATES are deliberately out of scope here: no record exists to hold a
     * capability on, and creation authority is the INSTANCES_CREATE permission plus the
     * transactional quota, InstanceImagePolicy and InstancePlacement. The restore path
     * (InstanceBackups) also creates rather than updates, which is why it needs no
     * exemption. Internal UPDATES that belong to an already-authorized operation stamp
     * status through the fenced, hook-free updateAll; the one that does not is destroy's
     * deleted_at save, which runs inside {@link #inAuthorizedOperation}.
     *
     * @throws Violations {@code instance_not_permitted} (the SAME uniform refusal
     *         requireOperationCapability raises, so the pair is never a capability
     *         oracle) or {@code tenant_field_frozen} on the offending column
     */
    private static void checkInstanceWrite(@NonNull Row row) {
        Model model = Models.get(InstanceModel.class);
        Object idValue = row.has(InstanceModel.ID.getName())
            ? row.get(InstanceModel.ID) : null;
        Row stored = idValue != null ? model.findById(idValue) : null;
        if (stored == null) {
            return;
        }

        AccessContext ctx = acting();
        if (ctx == null || ctx.isAnonymous()
                || !ctx.hasCapability(InstanceModel.MODEL_ID, idValue, HohenheimAccess.CONFIG)) {
            throw Violations.ofForm(Microcopy.of("instance_not_permitted")
                .withFilter("scope", "violations"));
        }

        for (Field<?, ?> field : model.getSchema().getFields().values()) {
            String name = field.getName();
            if (INSTANCE_TENANT_WRITABLE.contains(name) || INSTANCE_DERIVED.contains(name)
                    || !row.has(name)) {
                continue;
            }
            if (!Objects.equals(row.get(name), stored.get(name))) {
                throw Violations.ofField(name, row.get(name),
                    CmsSupport.violationText("tenant_field_frozen"));
            }
        }

        checkInstanceSettingsWrite(row, stored);
    }

    /**
     * The per-KEY half of the frozen-column rule over {@code settings}: a tenant may move
     * only the members {@code InstanceImagePolicy} judges, and every other member is as
     * frozen as a frozen column.
     *
     * AIDEV-NOTE: the settings SCHEMA is per-KIND (IncusContainerKind declares
     * {@code privileged}, the Docker kinds do not), so this walks the keys actually
     * present on either side rather than a model schema -- a kind-specific escape hatch
     * must not become writable just because InstanceModel's own schema cannot name it.
     * Comparison is by value, so a form echoing an unchanged member back is not a change,
     * exactly like the column loop above.
     *
     * @throws Violations {@code tenant_field_frozen} on {@code settings.<key>}
     */
    private static void checkInstanceSettingsWrite(@NonNull Row row, @NonNull Row stored) {
        String column = InstanceModel.SETTINGS.getName();
        if (!row.has(column)) {
            return;
        }
        Map<?, ?> staged = row.get(column) instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> current = stored.get(column) instanceof Map<?, ?> map ? map : Map.of();

        Set<Object> keys = new LinkedHashSet<>();
        keys.addAll(staged.keySet());
        keys.addAll(current.keySet());

        for (Object key : keys) {
            String name = String.valueOf(key);
            if (InstanceImagePolicy.JUDGED_SETTINGS_KEYS.contains(name)) {
                continue;
            }
            if (!Objects.equals(staged.get(key), current.get(key))) {
                throw Violations.ofField(column + "." + name, staged.get(key),
                    CmsSupport.violationText("tenant_field_frozen"));
            }
        }
    }

    // --- Managed databases -------------------------------------------------------------

    /**
     * The only columns a delegated tenant may author on a managed database: NONE. A
     * database record DESCRIBES a provisioned container, is immutable after create
     * ({@code DatabaseResource.updatable() == false}), and every column on it is either a
     * placement/execution decision or a credential the runtime must agree with.
     *
     * Why each of the load-bearing ones is frozen, so a future reader does not "helpfully"
     * open one:
     *
     * {@code server_id} is placement, and placement is operator authority
     * (InstancePlacement exists because a tenant naming a host is the thing it prevents).
     * {@code image} is the {@code image_any} threat one tier over -- DatabaseContainerKind
     * declares {@code tenantAuthored() == false} and therefore has no InstanceImagePolicy
     * equivalent to catch an attacker-chosen engine image. {@code memory_limit_mb} and
     * {@code cpu_limit} ARE the capacity booking (charge == cap), so writing one is
     * writing your own host budget. {@code ephemeral} flips the data directory to a tmpfs:
     * a silent total data loss on the next deploy. {@code db_user}/{@code db_password}/
     * {@code db_name} are mirrored into the RUNNING engine's secrets
     * (DatabaseInstances.writeEngineSecrets) -- rotation is a real operation, but it is a
     * SERVICE op that rewrites both sides, never a column write, because a raw write
     * desynchronizes the record from the engine and locks everyone out.
     */
    private static final Set<String> DATABASE_TENANT_WRITABLE = Set.of();

    /** Columns the write pipeline DERIVES; comparing them would refuse ordinary saves. */
    private static final Set<String> DATABASE_DERIVED = Set.of(
        DatabaseModel.ID.getName(),
        DatabaseModel.CREATED_AT.getName(),
        DatabaseModel.UPDATED_AT.getName());

    /** Nesting depth of {@link #inDatabaseAllocation} on this thread. */
    private static final ThreadLocal<Integer> DATABASE_ALLOCATION =
        ThreadLocal.withInitial(() -> 0);

    /**
     * Run the ONE funnel that may insert a managed-database row on a tenant's behalf
     * ({@code TenantDatabases.allocate}), which DERIVES every column of it: the stored
     * name, the credentials, the engine image, the placement and the limits.
     *
     * AIDEV-NOTE: a create is admitted by SCOPE rather than by a column whitelist, and
     * that is the stronger rule, not a shortcut. A whitelist would have to name the
     * columns the funnel itself writes, which are exactly the dangerous ones -- so it
     * would admit a direct POST carrying them. Outside this scope no tenant-originated
     * write reaches {@code managed_databases} at all, which also makes a column added
     * later frozen by DEFAULT, the property the domain whitelist exists for.
     */
    public static void inDatabaseAllocation(@NonNull Runnable body) {
        DATABASE_ALLOCATION.set(DATABASE_ALLOCATION.get() + 1);
        try {
            body.run();
        } finally {
            int depth = DATABASE_ALLOCATION.get() - 1;
            if (depth <= 0) {
                DATABASE_ALLOCATION.remove();
            } else {
                DATABASE_ALLOCATION.set(depth);
            }
        }
    }

    /**
     * Refuse a tenant database write: a create outside the allocation funnel, or any
     * column change at all on a stored record.
     *
     * @throws Violations {@code tenant_database_not_allocatable} or
     *         {@code tenant_field_frozen} on the offending column
     */
    private static void checkDatabaseWrite(@NonNull Row row) {
        Model model = Models.get(DatabaseModel.class);
        Object idValue = row.has(DatabaseModel.ID.getName()) ? row.get(DatabaseModel.ID) : null;
        Row stored = idValue != null ? model.findById(idValue) : null;

        if (stored == null) {
            if (DATABASE_ALLOCATION.get() <= 0) {
                throw Violations.ofForm(
                    CmsSupport.violationText("tenant_database_not_allocatable"));
            }
            return;
        }

        for (Field<?, ?> field : model.getSchema().getFields().values()) {
            String name = field.getName();
            if (DATABASE_TENANT_WRITABLE.contains(name) || DATABASE_DERIVED.contains(name)
                    || !row.has(name)) {
                continue;
            }
            if (!Objects.equals(row.get(name), stored.get(name))) {
                throw Violations.ofField(name, row.get(name),
                    CmsSupport.violationText("tenant_field_frozen"));
            }
        }
    }

    /**
     * Attaching a database to a site injects that database's CREDENTIALS into that site's
     * runtime, so it needs authority over BOTH records -- the two-sided
     * {@code GameDomains.requireAuthority} shape, and for the same reason: a one-sided
     * check turns a link row into a way to read a credential you were never granted (point
     * your own site at my database) or to hand your database to a runtime you do not
     * control (point my site at your database).
     *
     * Deliberately NOT a capability of its own: there is no join record to hold one on
     * before it exists, and a third authority over a pair is a third authority that can
     * disagree with the two it sits between.
     *
     * @throws Violations {@code tenant_site_not_managed} or the uniform database refusal
     */
    private static void requireLinkAuthority(@Nullable Object siteIdValue,
                                             @Nullable Object databaseIdValue) {
        AccessContext ctx = acting();
        if (!(siteIdValue instanceof Integer siteId) || ctx == null || ctx.isAnonymous()
                || !HohenheimAccess.canManageSite(ctx, siteId)) {
            throw Violations.ofField(SiteDatabaseModel.SITE_ID.getName(), siteIdValue,
                CmsSupport.violationText("tenant_site_not_managed"));
        }
        if (!(databaseIdValue instanceof Integer databaseId)
                || !HohenheimAccess.hasDatabaseCapability(ctx, databaseId,
                    HohenheimAccess.MANAGE)) {
            throw HohenheimAccess.databaseRefusal();
        }
    }

    /**
     * Attaching a database to an INSTANCE injects that database's CREDENTIALS into that
     * workload's environment, so it needs authority over BOTH records -- the same
     * two-sided rule {@link #requireLinkAuthority} applies to sites, and for the same
     * reason: a one-sided check turns a link row into a way to read a credential you were
     * never granted (point your own instance at my database) or to hand your database to a
     * runtime you do not control (point my instance at your database).
     *
     * The instance side asks {@code config}, NOT {@code manage}, and that is the narrower
     * of the two rather than a weaker one. {@code CONFIG} is declared as "author what the
     * instance IS", {@code GameDomains.requireInstanceAuthority} already asks exactly it
     * for the other instance-side join, and {@code InstanceVariables} asks it for a
     * variable write with the argument that a variable write IS a config write -- an attach
     * is precisely a variable write, performed by proxy. {@code MANAGE} implies
     * {@code CONFIG}, so an owner passes either way; a config-only delegate now passes too,
     * deliberately.
     *
     * The database side asks {@code manage}, not {@code credentials}, even though the
     * attach hands out a credential. A {@code credentials} holder can already READ the
     * password (ManageDatabaseCredentialsPage) and type it anywhere; what they cannot do is
     * make the engine REACHABLE from another workload, which is exactly what the link
     * network the attach creates does. Widening exposure is a manage-level act, and
     * DatabaseModel has no {@code config} verb by deliberate declaration.
     *
     * PUBLIC because {@code InstanceDatabaseResource.validate} must ask the SAME question
     * BEFORE its reachability lookups: those lookups are unscoped, so run first they were
     * an existence/name/host oracle for a probing tenant (absent, wrong-host-with-name,
     * and not-yours each answered differently). One derivation, asked early for the
     * refusal order and again by the hook as the gate. Callers guard with
     * {@link #isTenantOriginated()} themselves, exactly like the hook does.
     *
     * @throws Violations {@code tenant_instance_not_managed} or the uniform database refusal
     */
    public static void requireInstanceLinkAuthority(@Nullable Object instanceIdValue,
                                                    @Nullable Object databaseIdValue) {
        AccessContext ctx = acting();
        if (!(instanceIdValue instanceof Integer instanceId) || ctx == null || ctx.isAnonymous()
                || !HohenheimAccess.hasInstanceCapability(ctx, instanceId,
                    HohenheimAccess.CONFIG)) {
            throw Violations.ofField(InstanceDatabaseModel.INSTANCE_ID.getName(), instanceIdValue,
                CmsSupport.violationText("tenant_instance_not_managed"));
        }
        if (!(databaseIdValue instanceof Integer databaseId)
                || !HohenheimAccess.hasDatabaseCapability(ctx, databaseId,
                    HohenheimAccess.MANAGE)) {
            throw HohenheimAccess.databaseRefusal();
        }
    }

    // --- Instance variables ----------------------------------------------------------

    /**
     * The owning instance of a variable row a write is about to land, reading the stored
     * row when the submit carries no owner column (a partial update).
     */
    private static @Nullable Object instanceOwnerOf(@NonNull Row row) {
        if (row.has(InstanceVariableModel.INSTANCE_ID.getName())) {
            return row.get(InstanceVariableModel.INSTANCE_ID);
        }
        Object id = row.has(InstanceVariableModel.ID.getName())
            ? row.get(InstanceVariableModel.ID) : null;
        Row stored = id == null ? null
            : Models.get(InstanceVariableModel.class).findById(id);
        return stored == null ? null : stored.get(InstanceVariableModel.INSTANCE_ID);
    }

    /**
     * Refuse a tenant write to an INSTANCE-owned variable value without {@code config}
     * on that instance.
     *
     * AIDEV-NOTE: this is the model-layer half of the same decision
     * InstanceVariables.requireVariableAuthority makes on the service. Both exist on
     * purpose: the service gate is the one that produces the refusal on the funnels, and
     * this one holds when a future surface writes the row directly -- which is precisely
     * how the /api/v1 variable lane came to be the copy that lost the check. A variable
     * substitutes into {@code command}/{@code cloud_init} at deploy, so authoring one is
     * authoring what runs, which is what {@link HohenheimAccess#CONFIG} means.
     *
     * AIDEV-NOTE: ENVIRONMENT-owned rows (a null instance owner) pass here. They belong to
     * a project, hold no instance capability to ask about, and are gated by
     * PaasApi.visibleEnvironment -- which is now ADMIN-ONLY, the same permission
     * EnvironmentVariableResource's panel demands, so both surfaces finally agree. Do not
     * "helpfully" refuse them -- that would break the shipped project env lane while
     * protecting nothing this hook can decide.
     *
     * @throws Violations {@code instance_not_permitted}, the SAME uniform refusal
     *         requireOperationCapability raises, so the pair is never a capability oracle
     */
    private static void requireVariableConfig(@Nullable Object instanceId) {
        if (instanceId == null) {
            return;
        }
        AccessContext ctx = acting();
        if (ctx == null || ctx.isAnonymous()
                || !ctx.hasCapability(InstanceModel.MODEL_ID, instanceId,
                    HohenheimAccess.CONFIG)) {
            throw Violations.ofForm(Microcopy.of("instance_not_permitted")
                .withFilter("scope", "violations"));
        }
    }

    // --- DNS records -----------------------------------------------------------------

    /**
     * Refuse a tenant DNS write outside the type allow-list, or one that re-homes or
     * re-labels the row.
     *
     * @throws Violations anchored on the offending field
     */
    private static void checkRecordWrite(@NonNull Row row) {
        Model model = Models.get(DnsRecordModel.class);
        Row stored = row.has(DnsRecordModel.ID.getName())
            ? model.findById(row.get(DnsRecordModel.ID)) : null;

        requireRecordAuthority(row, stored);
        refuseForeignRecordType(effective(row, stored, DnsRecordModel.TYPE));
        // A record being EDITED must have been allow-listed before the edit too, otherwise
        // "change the type of the NS row to A" launders it in.
        if (stored != null) {
            refuseForeignRecordType(stored.get(DnsRecordModel.TYPE));
        }

        // Moving a row between zones is a takeover primitive: it carries a name a tenant may
        // hold into a zone they do not.
        if (stored != null && row.has(DnsRecordModel.ZONE_ID.getName())
                && !Objects.equals(row.get(DnsRecordModel.ZONE_ID),
                    stored.get(DnsRecordModel.ZONE_ID))) {
            throw Violations.ofField(DnsRecordModel.ZONE_ID.getName(),
                row.get(DnsRecordModel.ZONE_ID),
                CmsSupport.violationText("tenant_zone_frozen"));
        }

        // managed_by drives the zone-file import's replace scope (it replaces only rows where
        // it is NULL), so writing it is writing that import's blast radius.
        Object storedManagedBy = stored != null ? stored.get(DnsRecordModel.MANAGED_BY) : null;
        if (row.has(DnsRecordModel.MANAGED_BY.getName())
                && !Objects.equals(row.get(DnsRecordModel.MANAGED_BY), storedManagedBy)) {
            throw Violations.ofField(DnsRecordModel.MANAGED_BY.getName(),
                row.get(DnsRecordModel.MANAGED_BY),
                CmsSupport.violationText("tenant_managed_by_frozen"));
        }
    }

    /**
     * WHOSE name this is. The type allow-list says what a tenant may author; this says where.
     *
     * Two lanes, and they are a UNION of authorities rather than two policies that can
     * disagree: HOSTNAME authority (the row's FQDN is covered by a domain of a site the
     * caller manages -- the same {@link HostnameAuthority} predicate a certificate order
     * asks) and an explicit per-record GRANT ({@code edit}, or {@code dyndns} narrowed to the
     * dynamic columns). Renaming or creating additionally needs hostname authority over the
     * name being CLAIMED, or a grant would launder into a takeover of a name it never covered.
     *
     * @throws Violations naming the field that carries the claim
     */
    private static void requireRecordAuthority(@NonNull Row row, @Nullable Row stored) {
        AccessContext ctx = acting();
        if (ctx == null) {
            return;
        }

        // AIDEV-NOTE: the ANONYMOUS lane is /nic/update and nothing else. It is already
        // authorized -- by a bearer token resolved against the credential table's digest
        // before any write -- so it is not asked for a capability it structurally cannot
        // hold. It is bounded instead: a stored row holding a dyndns CREDENTIAL, and
        // VALUE the only column that may move. Without that bound the exemption would be
        // the bypass shape it looks like.
        if (ctx.isAnonymous()) {
            if (stored == null
                    || DynamicDnsService.credentialFor(stored.get(DnsRecordModel.ID)) == null) {
                throw refusal(DnsRecordModel.NAME.getName(), effective(row, stored, DnsRecordModel.NAME));
            }
            refuseChangesOutside(row, stored, Set.of(DnsRecordModel.VALUE.getName()));
            return;
        }

        Object recordId = stored != null ? stored.get(DnsRecordModel.ID) : null;
        boolean holdsEdit = recordId != null
            && ctx.hasCapability(DnsRecordModel.MODEL_ID, recordId, HohenheimAccess.EDIT);

        HostnameAuthority.Snapshot snapshot = HostnameAuthority.Snapshot.load();
        boolean ownsStoredName = stored != null
            && HostnameAuthority.canManage(snapshot, ctx, fqdnOf(stored, stored));

        // A CREATE has no row to hold authority over, so it answers to the claim half
        // alone. (A dyndns grant is authority over the CREDENTIAL table only -- see
        // checkCredentialWrite -- never over any column of the record itself.)
        if (stored != null && !holdsEdit && !ownsStoredName) {
            throw refusal(DnsRecordModel.NAME.getName(), effective(row, stored, DnsRecordModel.NAME));
        }

        String claimed = fqdnOf(row, stored);
        boolean claimsNewName = stored == null
            || !claimed.equals(fqdnOf(stored, stored));
        if (claimsNewName && !HostnameAuthority.canManage(snapshot, ctx, claimed)) {
            throw refusal(DnsRecordModel.NAME.getName(), claimed);
        }
    }

    /** @return the fully qualified name the write ends up with, or "" when its zone is gone */
    private static @NonNull String fqdnOf(@NonNull Row row, @Nullable Row stored) {
        Object zoneId = effective(row, stored, DnsRecordModel.ZONE_ID);
        Object name = effective(row, stored, DnsRecordModel.NAME);
        if (zoneId == null || name == null) {
            return "";
        }
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        String origin = zone != null ? zone.get(DnsZoneModel.ORIGIN) : null;
        return origin != null ? DnsNames.absolute(origin, String.valueOf(name)) : "";
    }

    /** Every column outside {@code writable} must keep its stored value (its default on a create). */
    private static void refuseChangesOutside(@NonNull Row row, @Nullable Row stored,
                                             @NonNull Set<String> writable) {
        Model model = Models.get(DnsRecordModel.class);
        for (Field<?, ?> field : model.getSchema().getFields().values()) {
            String name = field.getName();
            if (writable.contains(name) || RECORD_DERIVED.contains(name) || !row.has(name)) {
                continue;
            }
            Object baseline = stored != null ? stored.get(name) : field.getDefaultValue();
            if (!Objects.equals(row.get(name), baseline)) {
                throw refusal(name, row.get(name));
            }
        }
    }

    /**
     * A tenant write to a dyndns credential row: only a holder of the DYNDNS grant on
     * the referenced record may arm, re-key or revoke it. Anonymous callers hold nothing
     * (/nic/update updates the ADDRESS, it never touches credentials).
     *
     * @throws Violations on any refusal
     */
    private static void checkCredentialWrite(@Nullable Object recordId) {
        AccessContext ctx = acting();
        boolean authorized = ctx != null && !ctx.isAnonymous() && recordId != null
            && ctx.hasCapability(DnsRecordModel.MODEL_ID, recordId, HohenheimAccess.DYNDNS);
        if (!authorized) {
            throw refusal(DnsDyndnsCredentialModel.RECORD_ID.getName(), recordId);
        }
    }

    private static @NonNull Violations refusal(@NonNull String field, @Nullable Object value) {
        return Violations.ofField(field, value,
            CmsSupport.violationText("tenant_record_not_authorized"));
    }

    /**
     * The affordance twin of {@link #requireRecordAuthority} and the record delete hook:
     * whether Edit/Delete on this STORED record can ever succeed for this context. A
     * BOOLEAN for {@code updatableBy}/{@code deletableBy}, never a gate -- the hooks stay
     * the enforcement -- and it lives here so the offered affordance and the refusing
     * lane are one derivation. Mirrors the lanes exactly: an operator passes (the hooks
     * only run tenant-originated), a tenant needs a tenant-authorable TYPE plus either
     * the per-record {@code edit} grant or hostname authority over the stored FQDN.
     */
    public static boolean mayAuthorRecord(@NonNull AccessContext ctx, @NonNull Row stored) {
        if (HohenheimAccess.isAdmin(ctx)) {
            return true;
        }
        if (ctx.isAnonymous()) {
            return false;
        }
        if (!RECORD_TYPES.contains(String.valueOf(stored.get(DnsRecordModel.TYPE)))) {
            return false;
        }
        Object recordId = stored.get(DnsRecordModel.ID);
        if (recordId != null
                && ctx.hasCapability(DnsRecordModel.MODEL_ID, recordId, HohenheimAccess.EDIT)) {
            return true;
        }
        return HostnameAuthority.canManage(HostnameAuthority.Snapshot.load(), ctx,
            fqdnOf(stored, stored));
    }

    private static void refuseForeignRecordType(@Nullable Object type) {
        String text = type != null ? String.valueOf(type) : "";
        if (!RECORD_TYPES.contains(text)) {
            throw Violations.ofField(DnsRecordModel.TYPE.getName(), text,
                CmsSupport.violationText("tenant_record_type"));
        }
    }

    /**
     * The rows a criteria delete is about to remove, whatever model it targets.
     *
     * AIDEV-NOTE: a remove context carries CRITERIA, not rows -- the same re-query idiom
     * GeneratedDnsRecords.doomedRows uses, and for the same reason: enforcing on the
     * resource's delete method would leave every criteria delete outside the guard.
     */
    private static @NonNull List<Row> doomedRows(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        QueryContext queryContext = context.getQueryContext();
        if (model == null || queryContext == null) {
            return List.of();
        }
        return model.executeFindQuery(new QueryContext(
            queryContext.getCriteria(), List.of(), null, null, List.of(), null,
            queryContext.getLocaleChain(),
            queryContext.isAcrossLocales(), true, true, queryContext.getHints()));
    }

    /** The value the write will END UP with, reading the already-loaded stored row. */
    private static @Nullable Object effective(@NonNull Row row, @Nullable Row stored,
                                              @NonNull Field<?, ?> field) {
        if (row.has(field.getName())) {
            return row.get(field.getName());
        }
        return stored != null ? stored.get(field.getName()) : field.getDefaultValue();
    }

    private static boolean isPresent(@Nullable Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }
}
