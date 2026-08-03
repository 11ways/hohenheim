package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.server.GrantableModel;
import be.elevenways.zenit.auth.server.RecordGrantCapabilityChecker;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.KnownCapability;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.RecordCapabilityRules;
import be.elevenways.zenit.common.security.WebSocketAuthenticator;
import be.elevenways.zenit.server.data.RecordSourceGate;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The ONE per-site access policy funnel: v1 uses a SINGLE capability string
 * ({@link #MANAGE}) on the {@code hohenheim:site} model covering view, edit
 * and operate together -- finer verbs can be added later without any schema
 * change, since grants are plain (subject, model, record, capability) tuples.
 * Per-record decisions ride the framework's fixed precedence walk
 * ({@code RecordCapabilities}) through the rules declared in
 * {@link #declareGrantableModels}: {@code hohenheim.admin.access} is the admin
 * bypass and an EXPLICIT denial of {@code hohenheim.manage.access} (the gate)
 * kills every record grant.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.2.0
 */
public final class HohenheimAccess {

    /** The single v1 capability on a site record. */
    public static final String MANAGE = "manage";

    /** Read a record's own state: DNS record fields, certificate status (never key material). */
    public static final String VIEW = "view";

    /** Author a DNS record inside the delegated type allow-list. */
    public static final String EDIT = "edit";

    /** Mint and hold a DNS record's dyndns update token. */
    public static final String DYNDNS = "dyndns";

    /** Order or renew a certificate for names the holder already answers for. */
    public static final String REQUEST = "request";

    private HohenheimAccess() {
    }

    /**
     * The boot-time half of this policy. Sites are the ONE model here that holds
     * record grants, and zenit-auth refuses a grant on an undeclared model;
     * declaring it is also what keeps the grant-cleanup hooks off every other
     * model's deletes. The capability VOCABULARY (manage is delegable, so a
     * holder may mint the {@code cap:hohenheim:site#manage} API-key scope) and
     * the walk's composition RULES land here too, so the enforcement path and
     * the delegation path can never see different policies.
     */
    public static void declareGrantableModels() {
        // AIDEV-NOTE: liveWhen is NOT optional here. Sites soft-delete by hand -- the
        // resource stamps deleted_at through save() without SoftDeleteBehaviour attached --
        // so a trashed site's row is still physically present. Without this predicate the
        // framework's presence-only default counted it as alive: its grants survived the
        // orphan sweep and came straight back the moment the site was restored, handing an
        // operator authority the delete had already withdrawn. The SAME predicate also
        // stops a new grant being planted on a trashed site.
        RecordGrants.declareGrantable(GrantableModel.of(SiteModel.MODEL_ID)
            .liveWhen(row -> row.get(SiteModel.DELETED_AT) == null));
        KnownCapabilities.register(SiteModel.MODEL_ID,
            KnownCapability.of(MANAGE)
                .label(Microcopy.of("manage").withFilter("scope", "capability"))
                .elevated()
                .asDelegable());
        RecordGrantCapabilityChecker.declareRules(SiteModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS));

        // AIDEV-NOTE: DnsZoneModel, DnsPeerModel and DnsZonePeerModel declare NO vocabulary
        // and are NOT grantable, PERMANENTLY and by decision (docs/instance-tier-plan.md,
        // "Phase 2 parallel gate", DECIDED 2026-08-02). A zone row is the DNSSEC/TSIG trust
        // root (dnssec_private_key, tsig_secret, api_key) and every remaining field is SOA
        // policy whose blast radius is the whole zone going dark, so there is no per-field
        // split leaving a tenant a safe subset; creating a zone also ASSERTS a delegation
        // from the parent that hohenheim cannot verify. A tenant never sees a zone row, only
        // names inside one. Do not "helpfully" add one here: the tenant-facing DNS surface is
        // ManageDnsRecordResource, scoped by hostname authority and per-record grants.
        // AIDEV-NOTE: asOwnerImplied() on these two is DECLARED but INERT today -- the walk's
        // owner row only runs when the model's rules name an ownerField, and dns_records has
        // no owning-principal column. It is written down anyway because the decision is that
        // ownership WOULD imply them; the day a column lands, ownedBy() is the only edit.
        // CertificateModel's owner row is live (requested_by_user_id).
        RecordGrants.declareGrantable(GrantableModel.of(DnsRecordModel.MODEL_ID));
        KnownCapabilities.register(DnsRecordModel.MODEL_ID,
            KnownCapability.of(VIEW)
                .label(Microcopy.of("view").withFilter("scope", "capability"))
                .asDelegable()
                .asOwnerImplied(),
            KnownCapability.of(EDIT)
                .label(Microcopy.of("edit").withFilter("scope", "capability"))
                .elevated()
                .asDelegable()
                .asOwnerImplied(),
            // NOT delegable: the minted token is a bearer credential that SURVIVES grant
            // revocation, so re-delegation would launder a permanent capability out of a
            // revocable one. NS/CAA/MX/DS/DNSKEY authoring, managed_by mutation and zone_id
            // reassignment are deliberately not capabilities AT ALL -- each is a
            // zone-compromise primitive, refused in the write pipeline (TenantWrites) for
            // every writer rather than offered as something an operator could grant.
            KnownCapability.of(DYNDNS)
                .label(Microcopy.of("dyndns").withFilter("scope", "capability"))
                .elevated());
        RecordGrantCapabilityChecker.declareRules(DnsRecordModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS));

        // Instances: the SAME single-capability shape as sites, and registering it in the
        // SAME commit as the model is load-bearing -- without a declared vocabulary,
        // sameOwner on instances compares two EMPTY subject sets and answers "same owner"
        // for every pair: a tenancy check that cannot fail. Only "manage" for now; wider
        // verbs (view/power/console/config/destroy/exec) land WITH the surfaces that
        // enforce them (declaring one auto-attaches the grant-matrix subpage, so an
        // unenforced capability would be operator-editable theater).
        RecordGrants.declareGrantable(GrantableModel.of(InstanceModel.MODEL_ID)
            .liveWhen(row -> row.get(InstanceModel.DELETED_AT) == null));
        KnownCapabilities.register(InstanceModel.MODEL_ID,
            KnownCapability.of(MANAGE)
                .label(Microcopy.of("manage").withFilter("scope", "capability"))
                .elevated()
                .asDelegable());
        RecordGrantCapabilityChecker.declareRules(InstanceModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS));

        RecordGrants.declareGrantable(GrantableModel.of(CertificateModel.MODEL_ID));
        KnownCapabilities.register(CertificateModel.MODEL_ID,
            KnownCapability.of(VIEW)
                .label(Microcopy.of("view").withFilter("scope", "capability"))
                .asDelegable()
                .asOwnerImplied(),
            // Not owner-implied: having ordered one certificate is not authority to order the
            // next. Key EXPORT and certificate UPLOAD are not capabilities at all -- hohenheim
            // terminates TLS itself so a tenant never needs the key, and an uploaded
            // certificate is unverified authority over a name.
            KnownCapability.of(REQUEST)
                .label(Microcopy.of("request").withFilter("scope", "capability"))
                .elevated()
                .asDelegable());
        RecordGrantCapabilityChecker.declareRules(CertificateModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS)
                // The requester IS the owner: the column already exists because renewal
                // re-decides authority against it every sweep.
                .ownedBy(CertificateModel.REQUESTED_BY_USER_ID.getName()));
    }

    /**
     * Whether two records of one model answer to the SAME owner, which is what separates
     * a deliberate configuration from a cross-tenant seizure.
     *
     * AIDEV-NOTE: ownership is the record's set of {@link #MANAGE} grant SUBJECTS, never
     * an owner column -- InstanceModel deliberately has NO owner_principal_id, and this
     * method is THE one derivation every tier (routes, released claims, instances) answers
     * from; a second spelling is how two authorities drift. Two records an operator alone
     * controls hold no manage grants at all, so they compare equal and an admin may
     * deliberately point a wildcard at one site and carve one host out to another (a
     * shipped, dispatch-tested capability -- exact beats wildcard, and two upstreams need
     * two sites). The moment either side is TENANT-held, the subject sets differ and the
     * same shape becomes a takeover. Equality, not overlap: {A} versus {A, B} would let B
     * seize what A was serving. Mirrors WorkloadIdentity.isTenantManaged, which is the
     * same tenancy predicate one seam over.
     *
     * @return true when both records carry the same manage-grant subjects (both empty
     *         included), failing CLOSED to "different owners" when grants cannot be read
     */
    public static boolean sameOwner(@NonNull Identifier model, @NonNull Object firstId,
                                    @NonNull Object secondId) {
        // Grants key records by their stringified id, so identity folds the same way.
        if (String.valueOf(firstId).equals(String.valueOf(secondId))) {
            return true;
        }
        Set<String> first = manageSubjectsOf(model, firstId);
        Set<String> second = manageSubjectsOf(model, secondId);
        return first != null && second != null && first.equals(second);
    }

    /** Site convenience over {@link #sameOwner(Identifier, Object, Object)}. */
    public static boolean sameOwner(int firstSiteId, int secondSiteId) {
        return sameOwner(SiteModel.MODEL_ID, firstSiteId, secondSiteId);
    }

    /**
     * THE owner identity of a record: the subjects holding {@link #MANAGE} on it, spelled
     * {@code subjectType:subjectId}. An EMPTY set means operator-owned (nobody was granted
     * anything), which is why it is a legitimate value and never an error.
     *
     * AIDEV-NOTE: public because the released-claim ledger (ReleasedClaims) must STORE this
     * exact set at release time and compare a later claimant against it. It is the same
     * authority {@link #sameOwner} answers from -- a second spelling of "who owns this
     * record" is how the quarantine and the overlap refusal would end up disagreeing.
     *
     * @return the manage-grant subjects, or null when grants are unreadable (callers fail closed)
     */
    public static @Nullable Set<String> manageSubjectsOf(@NonNull Identifier model,
                                                         @NonNull Object recordId) {
        Set<String> subjects = new HashSet<>();
        try {
            for (Row grant : RecordGrants.listForRecord(model, recordId)) {
                if (MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))
                        && Boolean.TRUE.equals(grant.get(RecordGrantModel.VALUE))) {
                    subjects.add(grant.get(RecordGrantModel.SUBJECT_TYPE)
                        + ":" + grant.get(RecordGrantModel.SUBJECT_ID));
                }
            }
        } catch (IllegalStateException notInstalled) {
            // ZenitAuth.init never ran (tools, minimal tests): no tenants can exist, so
            // every record is operator-owned and the sets are legitimately equal.
            return subjects;
        } catch (RuntimeException unreadable) {
            return null;
        }
        return subjects;
    }

    /** Site convenience over {@link #manageSubjectsOf(Identifier, Object)}. */
    public static @Nullable Set<String> manageSubjectsOf(int siteId) {
        return manageSubjectsOf(SiteModel.MODEL_ID, siteId);
    }

    /**
     * @return true when the context holds the installation-wide admin permission
     */
    public static boolean isAdmin(@NonNull AccessContext ctx) {
        return ctx.hasPermission(HohenheimPanel.ACCESS);
    }

    /**
     * Whether the context holds {@link #MANAGE} on the site, decided by the
     * framework's precedence walk (admin bypass, gate denial, grants) -- never
     * by a grants-only lookup beside it.
     */
    public static boolean canManageSite(@NonNull AccessContext ctx, int siteId) {
        return ctx.hasCapability(SiteModel.MODEL_ID, siteId, MANAGE);
    }

    /**
     * Conduit convenience for HTTP handlers.
     */
    public static boolean canManageSite(@NonNull Conduit conduit, int siteId) {
        return canManageSite(RecordSourceGate.accessContextOf(conduit), siteId);
    }

    /**
     * Principal-only variant for WebSocket contexts (no conduit at open time):
     * the installed WebSocket authenticator is the sanctioned principal-only
     * path, and it rides the SAME precedence walk as the context variant.
     */
    public static boolean canManageSite(@NonNull Principal principal, int siteId) {
        return Zenit.getWebSocketAuthenticator()
            .hasCapability(principal, SiteModel.MODEL_ID, siteId, MANAGE);
    }

    /**
     * THE managed-site scoping shape, shared by every source and resource whose rows hang
     * off a site: admins are unconstrained, a principal with no managed sites matches
     * NOTHING, and everyone else gets the criteria {@code forManagedIds} spells over the
     * confirmed id set.
     *
     * AIDEV-NOTE: one definition on purpose. Three hand-rolled copies (site, domain,
     * certificate) is how one of them ends up missing the anonymous branch or answering
     * {@code ID.in(empty)}, which some backends widen instead of refusing.
     *
     * @param model          the model being scoped, for its {@code matchNone()}
     * @param forManagedIds  builds the criteria from the confirmed managed-site ids
     * @return null for admins (no extra constraint), else a criteria
     */
    public static @Nullable Criteria managedSiteScope(@NonNull AccessContext ctx,
                                                      @NonNull Model model,
                                                      @NonNull Function<Set<Integer>, Criteria> forManagedIds) {
        return grantScope(ctx, model, SiteModel.MODEL_ID, MANAGE, forManagedIds);
    }

    /**
     * The generalized shape of {@link #managedSiteScope}: admins are unconstrained, an
     * anonymous or grant-less principal matches NOTHING, and everyone else gets the criteria
     * {@code forGrantedIds} spells over the walk-confirmed record ids.
     *
     * @param model the model being SCOPED (its {@code matchNone()}), which is not necessarily
     *        the model the capability is held on -- domains scope by their parent site
     */
    public static @Nullable Criteria grantScope(@NonNull AccessContext ctx,
                                                @NonNull Model model,
                                                @NonNull Identifier capabilityModel,
                                                @NonNull String capability,
                                                @NonNull Function<Set<Integer>, Criteria> forGrantedIds) {
        if (isAdmin(ctx)) {
            return null;
        }
        if (ctx.isAnonymous()) {
            return model.matchNone();
        }
        Set<Integer> ids = grantedRecordIds(ctx, capabilityModel, capability);
        return ids.isEmpty() ? model.matchNone() : forGrantedIds.apply(ids);
    }

    /** Request-scoped memo of the confirmed id sets, keyed by model + capability. */
    private static final IdentifierKey<Map<String, Set<Integer>>> GRANTED_RECORD_IDS =
        IdentifierKey.of("hohenheim", "granted_record_ids");

    /**
     * Every site id the context holds {@link #MANAGE} on.
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull AccessContext ctx) {
        return grantedRecordIds(ctx, SiteModel.MODEL_ID, MANAGE);
    }

    /**
     * Every record id of {@code model} the context holds {@code capability} on, for feeding
     * scope criteria (the walk decides per record and offers no enumeration, so candidates
     * come from the grant store and each one is CONFIRMED through the walk -- which
     * re-applies admin bypass and gate denial).
     *
     * Memoized per REQUEST on the conduit (the PermissionResolver WALK_CACHE idiom): panel
     * eligibility, scope criteria and the nav probes all ask per render, and grants written
     * mid-request stay next-request-effective. Conduit-less contexts run the enumeration
     * fresh.
     *
     * AIDEV-NOTE: the memo is a MAP keyed by model+capability, not one attribute per set. One
     * attribute per set is how the second consumer (dns records) quietly ends up outside the
     * budget the first consumer's test pinned.
     */
    @NonNull
    public static Set<Integer> grantedRecordIds(@NonNull AccessContext ctx,
                                                @NonNull Identifier model,
                                                @NonNull String capability) {
        String key = model + "#" + capability;
        Conduit conduit = ctx.conduit();
        if (conduit == null) {
            return enumerateGrantedIds(ctx, model, capability);
        }

        Map<String, Set<Integer>> cache = conduit.getAttribute(GRANTED_RECORD_IDS);
        if (cache == null) {
            cache = new HashMap<>();
            try {
                conduit.setAttribute(GRANTED_RECORD_IDS, cache);
            } catch (UnsupportedOperationException attributeless) {
                // A conduit without attribute storage just pays the walk each call.
            }
        }

        Set<Integer> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        Set<Integer> ids = enumerateGrantedIds(ctx, model, capability);
        cache.put(key, ids);
        return ids;
    }

    private static @NonNull Set<Integer> enumerateGrantedIds(@NonNull AccessContext ctx,
                                                             @NonNull Identifier model,
                                                             @NonNull String capability) {
        return confirmedRecordIds(ctx.principal(), model, capability,
            id -> ctx.hasCapability(model, id, capability));
    }

    /**
     * Principal-only enumeration for conduit-less contexts, confirmed through
     * the same walk via the installed WebSocket authenticator.
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull Principal principal) {
        WebSocketAuthenticator authenticator = Zenit.getWebSocketAuthenticator();
        return confirmedRecordIds(principal, SiteModel.MODEL_ID, MANAGE,
            id -> authenticator.hasCapability(principal, SiteModel.MODEL_ID, id, MANAGE));
    }

    /**
     * @return the grant-derived candidate ids the walk confirms (unparseable ids skipped)
     */
    @NonNull
    private static Set<Integer> confirmedRecordIds(@NonNull Principal principal,
                                                   @NonNull Identifier model,
                                                   @NonNull String capability,
                                                   @NonNull Predicate<String> confirmedByWalk) {
        if (principal.isAnonymous()) {
            return Set.of();
        }
        Set<Integer> ids = new HashSet<>();
        for (String raw : RecordGrants.recordIds(principal, model, capability)) {
            if (!confirmedByWalk.test(raw)) {
                continue;
            }
            try {
                ids.add(Integer.parseInt(raw));
            } catch (NumberFormatException ignored) {
                // Grants store record ids as strings; these models all key on an integer.
            }
        }
        return ids;
    }
}
