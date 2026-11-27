package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import java.util.List;
import java.util.ArrayList;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.server.upstream.kinds.DevNamespaceUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.SiteManagerSettings;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.text.SlugText;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.page.ResourcePageEndpoints;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.edit.FormSecrets;
import be.elevenways.zenit.common.edit.submit.SubmittedValueCoercion;
import be.elevenways.zenit.common.orm.RecordRef;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.datasource.DuplicateKeyException;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.FieldRedaction;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.Applicability;
import be.elevenways.zenit.server.operation.Availability;
import be.elevenways.zenit.server.operation.Authorizer;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.RecordReadLines;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The handlers of the site operations, attached once per JVM: the switches, clone and rollback, and the site
 * resource's write verbs ({@link SiteWrites}).
 *
 * AIDEV-NOTE: attached in a static initializer like InstanceOperationHandlers; {@link #init()} only forces the class
 * to load before boot verifies every operation has its handler. Who may act is an authorizer per operation: reach of
 * the site (manage) for the switches, which is /manage's own row scope and every site for an operator, and
 * installation administration for clone, which only the admin panel ever offered; the rollback declares that same
 * administration as its operation's gate (SiteOperations.ROLLBACK_RELEASE), since its run is off the request, and
 * so answers a non-admin forbidden (the gate never reads the site, so it reveals nothing). Every other refusal
 * conceals the site as missing, the answer a caller gets for a site it cannot see. The write verbs follow
 * these gates: create and delete are installation administration, both edits reach of the site; the
 * delegated edit never runs the admin normalizers, while the model's write hooks (enable invariant, TenantWrites
 * column freeze, route claims, proxy reload) judge every writer alike.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class SiteOperationHandlers {

    /** The refusal of a rollback of a site that exposes no application. */
    private static final Microcopy NO_INSTANCE = HohenheimMicrocopy.VIOLATIONS.of("site_exposes_no_instance");

    static {
        // A switch to the state the site is in already says so and changes nothing; a trashed site is not found.
        OperationHandlers.attach(SiteOperations.ENABLE)
            .applies(site -> live(site) && !enabled(site))
            .notApplicable(site -> alreadySo(site, "already_online"))
            .authorize(reachesSite())
            .handle(call -> switchTo(call, true));
        // The panel's own site cannot be switched off from inside the panel: dead, with the reason on screen.
        OperationHandlers.attach(SiteOperations.DISABLE)
            .applies(site -> live(site) && enabled(site))
            .notApplicable(site -> alreadySo(site, "already_paused"))
            .availability((site, access) -> SiteParts.panelLockoutReason("toggle_self_lockout", site, access))
            .authorize(reachesSite())
            .handle(call -> switchTo(call, false));
        // Only a name forced to HTTPS that no working certificate covers is an error page this switches back.
        OperationHandlers.attach(SiteOperations.STOP_FORCING_HTTPS)
            .applies(Applicability.batch((sites, access, source) -> {
                Set<Integer> needing = AppHealth.needingCertificate(sites);
                return sites.stream().map(site -> live(site) && needing.contains(site.get(SiteModel.ID))).toList();
            }))
            .authorize(reachesSite())
            .handle(call -> stopForcingHttps(call.subject()));
        OperationHandlers.attach(SiteOperations.CLONE)
            .applies(SiteOperationHandlers::live)
            .authorize(administers())
            .handle(call -> cloneSite(call.subject(), Objects.requireNonNull(call.input(), "a clone has its input")));
        // A stream site's ports move into another live stream site; the emptied one is trashed as a delete trashes it.
        OperationHandlers.attach(SitePortMoves.MOVE)
            .applies(site -> live(site) && SitePortMoves.holdsPorts(site))
            .authorize(administers())
            .handle(call -> SitePortMoves.move(call.subject(), Objects.requireNonNull(call.input(),
                "a move has its input"), SiteOperationHandlers::deleteSite));
        // Only a site exposing an application through the instance upstream has a release to roll back. A background
        // run: what refuses it at once (no application, nothing kept) is asked here, before any run starts. Who may
        // ask is the operation's own gate (installation administration), never an authorizer here alone.
        OperationHandlers.attach(SiteOperations.ROLLBACK_RELEASE)
            .applies(site -> live(site)
                && InstanceUpstreamKind.ID.toString().equals(site.get(SiteModel.UPSTREAM_KIND)))
            .availability(Availability.batch((sites, access) -> rollbackRefusals(sites)))
            .handle(SiteOperationHandlers::rollback);
        OperationHandlers.attach(SiteWrites.CREATE)
            .authorize((none, input, access) -> HohenheimAccess.isAdmin(access) ? null
                : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "creating a site is installation administration"))
            .handle(call -> createSite(Objects.requireNonNull(call.input(), "a create has its input"),
                Objects.requireNonNull(call.access(), "a create has its caller")));
        OperationHandlers.attach(SiteWrites.UPDATE)
            .applies(SiteOperationHandlers::live)
            .authorize(reachesSite())
            .patchBase((site, access) -> editInput(Objects.requireNonNull(site, "an edit has its site")))
            .handle(call -> updateSite(call));
        OperationHandlers.attach(SiteWrites.MANAGE_UPDATE)
            .applies(SiteOperationHandlers::live)
            .authorize(reachesSite())
            .patchBase((site, access) -> manageInput(Objects.requireNonNull(site, "an edit has its site")))
            .handle(call -> updateDelegated(call));
        // A passthrough site never sees a path (TLS is not terminated here) and a stream is relayed unread on a port
        // of its own, so neither has anything to protect.
        OperationHandlers.attach(ProtectPath.OPERATION)
            .applies(site -> live(site) && SiteParts.servesHttp(site))
            .authorize(reachesSite())
            .handle(call -> protectPath(call.subject(), Objects.requireNonNull(call.input(), "protecting has input"),
                call.access()));
        // Deleting the panel's own site is the same outage as switching it off: dead, with the reason on screen.
        OperationHandlers.attach(SiteWrites.DELETE)
            .applies(SiteOperationHandlers::live)
            .availability((site, access) -> SiteParts.panelLockoutReason("delete_self_lockout", site, access))
            .authorize(administers())
            .handle(call -> deleteSite(call.subject()));
        // A live site set back to a saved revision; one in the trash comes out of it first (SiteWrites.RESTORE).
        OperationHandlers.attach(SiteWrites.RESTORE_REVISION)
            .applies(SiteOperationHandlers::live)
            .authorize(administers())
            .handle(call -> restoreRevision(call.subject(), Objects.requireNonNull(Objects.requireNonNull(
                call.input(), "a revision restore has its input").revision(), "a required revision"),
                Objects.requireNonNull(call.access(), "a restore has its caller")));
        // A dev namespace's lost registration token replaced by a new one, shown once to the operator who asked.
        OperationHandlers.attach(SiteOperations.REGENERATE_REGISTRATION_TOKEN)
            .applies(site -> live(site) && SiteKindWrites.DEV_NAMESPACE.holds(site))
            .handle(call -> SiteRegistrationTokens.regenerate(call.subject(),
                Objects.requireNonNull(call.access(), "a new token has its caller")));
        RecordReadLines.register(SiteModel.MODEL_ID, SiteRegistrationTokens.class, SiteRegistrationTokens::readLines);
    }

    private SiteOperationHandlers() {
    }

    /**
     * One dedicated (unshared) access list, its rules and the protected path, written in the operation's one command;
     * the protected-path invariant inside the path's save refuses a list that would let everyone through. The list
     * answers to whoever created it (the access-list create's own ownership step), so a tenant can manage it later.
     */
    private static @NonNull Integer protectPath(@NonNull Row site, ProtectPath.@NonNull Input input,
                                                @Nullable AccessContext access) {
        Integer listId = dedicatedList(site, input, access);
        ProtectedPathModel paths = Models.get(ProtectedPathModel.class);
        Row path = paths.createEmptyRow();
        path.set(ProtectedPathModel.SITE_ID, site.get(SiteModel.ID));
        path.set(ProtectedPathModel.PATH, input.path());
        path.set(ProtectedPathModel.ACCESS_LIST_ID, listId);
        paths.save(path);
        return path.get(ProtectedPathModel.ID);
    }

    /**
     * Writes the list a protection of one path makes, named after the site and the path, satisfied by any of its rules,
     * unshared and owned by the caller, one rule per person, network or provider the input names, or one signed-link
     * rule whose new secret waits for the caller: the list both the site's "Protect a path" and protected_path_set
     * point the path at.
     *
     * @return the list's key
     * @throws Violations when the chosen method's entry is empty, or a secret would be shown to nobody, before
     *                    anything is written
     */
    static @NonNull Integer dedicatedList(@NonNull Row site, ProtectPath.@NonNull Input input,
                                          @Nullable AccessContext access) {
        List<ProtectionRule> rules = protectionRules(input, access);
        AccessListModel lists = Models.get(AccessListModel.class);
        Row list = lists.createEmptyRow();
        list.set(AccessListModel.NAME, site.get(SiteModel.NAME) + " " + input.path());
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        list.set(AccessListModel.SHARED, false);
        lists.save(list);
        HohenheimAccess.grantCreatorManage(AccessListModel.MODEL_ID, list.get(AccessListModel.ID), access);
        AccessRuleModel ruleModel = Models.get(AccessRuleModel.class);
        int sort = 0;
        for (ProtectionRule rule : rules) {
            Row row = ruleModel.createEmptyRow();
            row.set(AccessRuleModel.ACCESS_LIST_ID, list.get(AccessListModel.ID));
            row.set(AccessRuleModel.SORT, sort++);
            row.set(AccessRuleModel.TYPE, rule.type());
            row.set(AccessRuleModel.DATA, rule.data());
            row.set(AccessRuleModel.ENABLED, true);
            ruleModel.save(row);
            if (rule.madeSecret() != null) {
                SignedLinkSecrets.parkNew(Objects.requireNonNull(access, "a made secret has its caller"),
                    list.get(AccessListModel.ID), rule.madeSecret());
            }
        }
        return list.get(AccessListModel.ID);
    }

    /**
     * The rules the chosen method writes, each a type and its data; an answer that names nobody is refused on its own
     * entry, before anything is written.
     *
     * @throws Violations when the chosen method's entry is empty
     */
    private static @NonNull List<ProtectionRule> protectionRules(ProtectPath.@NonNull Input input,
                                                                 @Nullable AccessContext access) {
        List<ProtectionRule> rules = new ArrayList<>();
        switch (input.method()) {
            case ProtectPath.METHOD_PASSWORD -> {
                for (Map<String, Object> person : input.people()) {
                    String username = Texts.trimmedOrNull(person.get("username"));
                    String password = Texts.trimmedOrNull(person.get("password"));
                    if (username != null && password != null) {
                        rules.add(new ProtectionRule(AccessRuleModel.TYPE_BASIC_AUTH, Map.<String, Object>of(
                            AccessRuleModel.BASIC_AUTH_USERNAME.getName(), username,
                            AccessRuleModel.BASIC_AUTH_PASSWORD.getName(),
                            Objects.requireNonNull(BasicCredentials.hashIfNeeded(password))), null));
                    }
                }
                requireSome(rules, ProtectPath.PEOPLE.name(), "protect_path_nobody");
            }
            case ProtectPath.METHOD_NETWORK -> {
                for (String network : input.networks()) {
                    rules.add(new ProtectionRule(AccessRuleModel.TYPE_IP_ALLOW,
                        Map.<String, Object>of(AccessRuleModel.NETWORK.getName(), network), null));
                }
                requireSome(rules, ProtectPath.NETWORKS.getName(), "protect_path_no_network");
            }
            case ProtectPath.METHOD_SIGN_IN -> {
                if (input.provider_id() != null) {
                    rules.add(new ProtectionRule(AccessRuleModel.TYPE_AUTH_PROVIDER,
                        Map.<String, Object>of(AccessRuleModel.PROVIDER_ID.getName(), input.provider_id()), null));
                }
                requireSome(rules, ProtectPath.PROVIDER_ID.getName(), "protect_path_no_provider");
            }
            case ProtectPath.METHOD_SIGNED_LINK -> {
                SignedLinkSecrets.NewSecret made = SignedLinkSecrets.first(access);
                rules.add(new ProtectionRule(AccessRuleModel.TYPE_SIGNED_LINK, made.data(), made.secret()));
            }
            default -> throw Violations.ofField(ProtectPath.METHOD.getName(), input.method(),
                HohenheimMicrocopy.VIOLATIONS.of("protect_path_method"));
        }
        return rules;
    }

    /**
     * One access rule the protection writes: its type token, its type-specific data and the secret it made, which is
     * parked for the caller once the rule is stored.
     */
    private record ProtectionRule(@NonNull String type, @NonNull Map<String, Object> data,
                                  @Nullable String madeSecret) {

        @Override
        public @NonNull String toString() {
            return "ProtectionRule[" + this.type + "]";
        }
    }

    private static void requireSome(@NonNull List<?> rules, @NonNull String entry, @NonNull String reason) {
        if (rules.isEmpty()) {
            throw Violations.ofField(entry, "", HohenheimMicrocopy.VIOLATIONS.of(reason));
        }
    }

    /** Loads the class, attaching the handlers; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /**
     * A trashed site takes none of these: it routes nothing until a restore, which runs its own route checks, so an
     * enable there would bypass them and a clone or rollback of it would act on a record the panels hide.
     */
    static boolean live(@NonNull Row site) {
        return !SiteModel.SOFT_DELETE.isTrashed(site);
    }

    private static boolean enabled(@NonNull Row site) {
        return Boolean.TRUE.equals(site.get(SiteModel.ENABLED));
    }

    static <I> @NonNull Authorizer<Row, I> reachesSite() {
        return (site, input, access) -> reaches(site, access) ? null : concealed(site);
    }

    /** @return whether the reader manages the site: who switches it, and who reads its access log */
    static boolean reaches(@NonNull Row site, @NonNull AccessContext access) {
        return HohenheimAccess.reachesRecord(access, SiteModel.MODEL_ID, site.get(SiteModel.ID),
            HohenheimCapabilities.MANAGE);
    }

    private static <I> @NonNull Authorizer<Row, I> administers() {
        return (site, input, access) -> HohenheimAccess.isAdmin(access) ? null : concealed(site);
    }

    static @NonNull DomainRefusal concealed(@NonNull Row site) {
        return new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "site " + site.get(SiteModel.ID) + " is not reachable");
    }

    /**
     * @param said the site scope's words for the state it is in
     * @return the refusal of a switch to the state a live site is in already, in words naming it; a trashed site's
     *         is not found, as a site the panels hide
     */
    private static @NonNull DomainRefusal alreadySo(@NonNull Row site, @NonNull String said) {
        if (!live(site)) {
            return new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "site " + site.get(SiteModel.ID)
                + " is in the trash");
        }
        String code = RecordCodes.codeUnjudged(SiteModel.MODEL_ID, String.valueOf(site.get(SiteModel.ID)));
        return new DomainRefusal(ZenitRefusalReason.OPERATION_UNAVAILABLE, "site " + site.get(SiteModel.ID)
            + " is " + said, HohenheimMicrocopy.SITE.of(said).withArg("site", code));
    }

    /**
     * Sets the site back to its revision through the operator's site resource, the history tab's own restore.
     *
     * @return the site's code and the revision it now reads as
     */
    private static @NonNull String restoreRevision(@NonNull Row site, int revision, @NonNull AccessContext caller) {
        Panel panel = ApiConduits.adminPanel();
        PanelResource<Row> sites = Objects.requireNonNull(CmsSupport.declaredRowEntry(panel, HohenheimSlugs.SITES),
            "the panel declares its sites");
        ResourceWrites.restoreRevision(panel, sites, site, revision, caller);
        String code = RecordCodes.codeUnjudged(SiteModel.MODEL_ID, String.valueOf(site.get(SiteModel.ID)));
        return caller.text(HohenheimMicrocopy.SITE.of("restored_revision").withArg("site", code)
            .withArg("revision", revision));
    }

    /**
     * Sets the site's switch; the write pipeline's enable invariant (SiteEnableInvariant) runs inside the save and
     * refuses a site whose hostnames another enabled site already routes, by name.
     */
    private static @Nullable Void switchTo(@NonNull OperationCall<Row, Void> call, boolean enable) {
        Row site = call.subject();
        site.set(SiteModel.ENABLED, enable);
        Models.get(SiteModel.class).save(site,
            Change.fact(enable ? HohenheimActivityAction.ENABLED : HohenheimActivityAction.DISABLED));
        return null;
    }

    /**
     * Switches HTTPS forcing off on each of the site's names that is forced while no working certificate covers it; a
     * name that has one keeps it. The write states force_ssl itself, so the certificate latch stays disarmed.
     */
    private static @Nullable Void stopForcingHttps(@NonNull Row site) {
        Set<String> working = AppHealth.workingNames();
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        for (Row domain : SiteParts.domainsOf(site)) {
            if (AppHealth.forcedUncovered(domain, working)) {
                domain.set(SiteDomainModel.FORCE_SSL, false);
                domains.save(domain);
            }
        }
        return null;
    }

    private static @Nullable Void rollback(@NonNull OperationCall<Row, Void> call) {
        Integer instanceId = call.subject().get(SiteModel.INSTANCE_ID);
        if (instanceId == null) {
            throw Violations.ofForm(NO_INSTANCE);
        }
        ReleaseEngine.rollback(instanceId, InstanceOperationHandlers.triggerOf(call.surface()));
        call.reportStepDone(HohenheimMicrocopy.DEPLOYMENTS.of("rollback_done"));
        return null;
    }

    /** @return per site, in order, why its rollback cannot start (no application, or nothing kept), else null */
    private static @NonNull List<@Nullable Microcopy> rollbackRefusals(@NonNull List<? extends Row> sites) {
        Set<Integer> applicationIds = new LinkedHashSet<>();
        for (Row site : sites) {
            Integer instanceId = site.get(SiteModel.INSTANCE_ID);
            if (instanceId != null) {
                applicationIds.add(instanceId);
            }
        }
        Map<Integer, Microcopy> refused = ReleaseEngine.rollbackRefusals(applicationIds);
        List<Microcopy> reasons = new ArrayList<>(sites.size());
        for (Row site : sites) {
            Integer instanceId = site.get(SiteModel.INSTANCE_ID);
            reasons.add(instanceId == null ? NO_INSTANCE : refused.get(instanceId));
        }
        return reasons;
    }

    /** @return a copy of the settings without the secret settings the kind declares; null for none */
    private static @Nullable Map<String, Object> withoutSecrets(@Nullable String kind,
                                                               @Nullable Map<String, Object> settings) {
        if (settings == null) {
            return null;
        }
        Map<String, Object> kept = new LinkedHashMap<>(settings);
        UpstreamKindHandler handler = kind == null ? null : UpstreamKindHandlers.getHandler(kind);
        if (handler != null) {
            for (Field<?, ?> field : handler.getSchema().getFields().values()) {
                if (FieldRedaction.hidesWholeValue(field)) kept.remove(field.getName());
            }
        }
        return kept;
    }

    /**
     * Clone a site and its domains under the name the operator gave; the copy starts disabled and carries NO bearer
     * credentials of its own.
     *
     * AIDEV-NOTE: the copy deliberately does NOT carry instance_id: two sites pointing at one instance is a second
     * front door to the same workload, not a copy of it, so the operator picks or creates the instance for the clone.
     * The slug's UNIQUE constraint is the whole name check, and it covers every row, soft-deleted ones included: a
     * read-then-insert both missed a trashed site's slug and lost the race to a concurrent clone.
     *
     * @return the copy's id
     * @throws Violations on the name when its slug is already a site's: the form shows it inline
     */
    private static @NonNull Integer cloneSite(@NonNull Row site, SiteOperations.@NonNull CloneInput input) {
        String name = input.name();
        if (name == null) {
            throw Violations.ofField(SiteOperations.CLONE_NAME.getName(), "",
                HohenheimMicrocopy.VIOLATIONS.of("name_required"));
        }
        SiteModel siteModel = Models.get(SiteModel.class);
        SiteDomainModel domainModel = Models.get(SiteDomainModel.class);

        Row clone = siteModel.createEmptyRow();
        clone.set(SiteModel.NAME, name);
        clone.set(SiteModel.SLUG, SlugText.slugify(name));
        clone.set(SiteModel.UPSTREAM_KIND, site.get(SiteModel.UPSTREAM_KIND));
        // NO bearer credentials: a secret setting of the kind (a dev namespace's registration token) is the original's
        // alone, so two sites never answer to one secret.
        clone.set(SiteModel.SETTINGS, withoutSecrets(site.get(SiteModel.UPSTREAM_KIND),
            RawValues.mapOrNull(site.get(SiteModel.SETTINGS))));
        clone.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        clone.set(SiteModel.ENABLED, false);
        clone.set(SiteModel.AUTH_PROVIDER_ID, site.get(SiteModel.AUTH_PROVIDER_ID));
        clone.set(SiteModel.ACCESS_LIST_ID, site.get(SiteModel.ACCESS_LIST_ID));
        try {
            siteModel.save(clone, Change.fact(HohenheimActivityAction.CLONED, Map.of(
                HohenheimActivityAction.ORIGINAL_KEY, new RecordRef(SiteModel.MODEL_ID,
                    String.valueOf((Object) site.get(SiteModel.ID))))));
        } catch (DuplicateKeyException conflict) {
            if (!SiteModel.SLUG.getName().equals(conflict.getColumnName())) {
                throw conflict;
            }
            throw Violations.ofField(SiteOperations.CLONE_NAME.getName(), name, ResourcePageEndpoints.DUPLICATE_VALUE);
        }

        int newSiteId = clone.get(SiteModel.ID);
        for (Row domain : domainModel.findBySiteId(site.get(SiteModel.ID))) {
            Row domainClone = domainModel.createEmptyRow();
            domainClone.set(SiteDomainModel.SITE_ID, newSiteId);
            domainClone.set(SiteDomainModel.HOSTNAME, domain.get(SiteDomainModel.HOSTNAME) + ".clone");
            domainClone.set(SiteDomainModel.MATCH_TYPE, domain.get(SiteDomainModel.MATCH_TYPE));
            domainClone.set(SiteDomainModel.FORCE_SSL, domain.get(SiteDomainModel.FORCE_SSL));
            domainClone.set(SiteDomainModel.FORCE_SSL_AUTO, domain.get(SiteDomainModel.FORCE_SSL_AUTO));
            domainClone.set(SiteDomainModel.HSTS_ENABLED, domain.get(SiteDomainModel.HSTS_ENABLED));
            domainClone.set(SiteDomainModel.HSTS_SUBDOMAINS, domain.get(SiteDomainModel.HSTS_SUBDOMAINS));
            domainClone.set(SiteDomainModel.PATH, domain.get(SiteDomainModel.PATH));
            domainClone.set(SiteDomainModel.STRIP_PATH, domain.get(SiteDomainModel.STRIP_PATH));
            domainClone.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT,
                domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT));
            domainClone.set(SiteDomainModel.LISTEN_ON, domain.get(SiteDomainModel.LISTEN_ON));
            domainClone.set(SiteDomainModel.CUSTOM_HEADERS, domain.get(SiteDomainModel.CUSTOM_HEADERS));
            domainClone.set(SiteDomainModel.RESPONSE_HEADERS, domain.get(SiteDomainModel.RESPONSE_HEADERS));
            domainModel.save(domainClone);
        }
        return newSiteId;
    }

    /**
     * Create the site and, when the form carried one, its first hostname, in the one write envelope the create runs
     * in.
     *
     * AIDEV-NOTE: the domain row goes through the ordinary {@code SiteDomainModel.save}, which is the whole point:
     * the claim checks (hostname required, route overlap, route taken, quarantine) live in that model's write pipeline
     * (SiteDomainRouteInvariant), so this create is refused by exactly the refusal a hand-made domain gets, pathed on
     * "hostname", and the envelope rolls the site back with it. Do NOT copy any part of that check up here.
     *
     * @param caller who creates it, shown once a registration token Hohenheim makes for it
     * @return the new site's id
     * @throws Violations on the name when it is blank, form-level when its slug is already a site's, on the
     *     registration token when one would be made and no account stands behind the caller
     */
    static @NonNull Integer createSite(SiteWrites.@NonNull CreateInput input, @NonNull AccessContext caller) {
        String name = requiredName(input.name());
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        // Derived once, at create (and clone), and STORED: nothing re-derives it from the name, so a rename keeps the
        // slug the site's paths and containers were named by.
        site.set(SiteModel.SLUG, SlugText.slugify(name));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.UPSTREAM_KIND, input.upstream_kind());
        site.set(SiteModel.INSTANCE_ID, input.instance_id());
        Map<String, Object> settings = settingsOf(input.upstream_kind(), input.settings(), null);
        String minted = SiteRegistrationTokens.mintInto(input.upstream_kind(), settings);
        SiteRegistrationTokens.requireShownTo(minted, caller);
        site.set(SiteModel.SETTINGS, settings);
        if (input.trusted_upstream() != null) {
            site.set(SiteModel.TRUSTED_UPSTREAM, input.trusted_upstream());
        }
        if (input.enabled() != null) {
            site.set(SiteModel.ENABLED, input.enabled());
        }
        site.set(SiteModel.DESCRIPTION, input.description());
        site.set(SiteModel.AUTH_PROVIDER_ID, input.auth_provider_id());
        site.set(SiteModel.ACCESS_LIST_ID, input.access_list_id());
        try {
            sites.save(site);
        } catch (DuplicateKeyException conflict) {
            if (!SiteModel.SLUG.getName().equals(conflict.getColumnName())) {
                throw conflict;
            }
            // The slug backs no form entry, so the row lane answered this form-level; the create keeps that answer.
            throw Violations.ofForm(ResourcePageEndpoints.DUPLICATE_VALUE);
        }
        int siteId = site.get(SiteModel.ID);
        String hostname = Texts.trimmedOrNull(input.hostname());
        if (hostname != null) {
            createFirstDomain(siteId, hostname);
        }
        SiteRegistrationTokens.saved(caller, siteId, null, settings, minted);
        return siteId;
    }

    /**
     * The minimal hostname row an operator creates by hand on the Domains tab (DomainParts' quick add: the hostname,
     * the site and the force-SSL default); the match type is derived by the model's own canonicalization hook.
     */
    private static void createFirstDomain(int siteId, @NonNull String hostname) {
        SiteDomainModel domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.FORCE_SSL, SiteDomainModel.FORCE_SSL.getDefaultValue());
        domainModel.save(domain);
    }

    /** The admin edit's current input: the stored columns its form edits, the patch a partial write lands on. */
    private static SiteWrites.@NonNull EditInput editInput(@NonNull Row site) {
        Map<String, Object> settings = RawValues.mapOrNull(site.get(SiteModel.SETTINGS));
        return new SiteWrites.EditInput(site.get(SiteModel.NAME), site.get(SiteModel.UPSTREAM_KIND),
            site.get(SiteModel.INSTANCE_ID), settings != null ? new LinkedHashMap<>(settings) : null,
            site.get(SiteModel.TRUSTED_UPSTREAM), site.get(SiteModel.ENABLED), site.get(SiteModel.DESCRIPTION),
            site.get(SiteModel.AUTH_PROVIDER_ID), site.get(SiteModel.ACCESS_LIST_ID));
    }

    /** The delegated edit's current input: its columns, and the manager's settings of the site's kind. */
    private static SiteWrites.@NonNull ManageInput manageInput(@NonNull Row site) {
        String kind = site.get(SiteModel.UPSTREAM_KIND);
        return new SiteWrites.ManageInput(site.get(SiteModel.NAME), site.get(SiteModel.ENABLED),
            site.get(SiteModel.DESCRIPTION), kind,
            SiteManagerSettings.posted(kind, RawValues.map(site.get(SiteModel.SETTINGS))));
    }

    /**
     * The operator's edit over the reviewed revision.
     *
     * AIDEV-NOTE: the enable invariant and the tls-passthrough refusals are NOT re-checked here: they run in the
     * SiteModel write pipeline (SiteEnableInvariant, the beforeValidate hook), which the save funnels through, one
     * enforcement point for every writer. Only the columns set here are saved ({@code saveWrittenRefusingGone}), so a
     * column the edit leaves alone keeps a concurrent writer's value, and a site deleted since its load is refused.
     *
     * @throws DomainRefusal STALE when the site moved past the revision the editor reviewed
     */
    private static @Nullable Void updateSite(@NonNull OperationCall<Row, SiteWrites.EditInput> call) {
        Row site = call.subject();
        requireReviewedRevision(site, call.expectedVersion());
        SiteWrites.EditInput input = Objects.requireNonNull(call.input(), "an edit has its input");
        AccessContext caller = Objects.requireNonNull(call.access(), "an edit has its caller");
        Set<String> written = call.patched();
        String storedKind = site.get(SiteModel.UPSTREAM_KIND);
        Map<String, Object> storedSettings = RawValues.map(site.get(SiteModel.SETTINGS));
        if (writes(written, SiteModel.NAME)) {
            site.set(SiteModel.NAME, requiredName(input.name()));
        }
        if (writes(written, SiteModel.UPSTREAM_KIND)) {
            site.set(SiteModel.UPSTREAM_KIND, input.upstream_kind());
        }
        if (writes(written, SiteModel.INSTANCE_ID)) {
            site.set(SiteModel.INSTANCE_ID, input.instance_id());
        }
        // The settings are read under their kind, so a new kind re-reads them even when they were not posted. A save
        // keeping the kind keeps the stored keys the kind does not declare, which its sub-form cannot post.
        boolean settingsWritten = writes(written, SiteModel.SETTINGS) || writes(written, SiteModel.UPSTREAM_KIND);
        String minted = null;
        if (settingsWritten) {
            Map<String, Object> settings = settingsOf(input.upstream_kind(), input.settings(), site);
            minted = SiteRegistrationTokens.mintInto(input.upstream_kind(), settings);
            SiteRegistrationTokens.requireShownTo(minted, caller);
            UpstreamKindHandler kept = Objects.equals(input.upstream_kind(), storedKind)
                ? UpstreamKindHandlers.getHandler(storedKind) : null;
            site.set(SiteModel.SETTINGS, kept == null ? settings
                : KindSettingsWrites.keptUndeclared(kept.getSchema(), storedSettings, settings));
        }
        if (writes(written, SiteModel.TRUSTED_UPSTREAM)) {
            site.set(SiteModel.TRUSTED_UPSTREAM, input.trusted_upstream());
        }
        if (writes(written, SiteModel.ENABLED)) {
            site.set(SiteModel.ENABLED, input.enabled());
        }
        if (writes(written, SiteModel.DESCRIPTION)) {
            site.set(SiteModel.DESCRIPTION, input.description());
        }
        if (writes(written, SiteModel.AUTH_PROVIDER_ID)) {
            site.set(SiteModel.AUTH_PROVIDER_ID, input.auth_provider_id());
        }
        if (writes(written, SiteModel.ACCESS_LIST_ID)) {
            site.set(SiteModel.ACCESS_LIST_ID, input.access_list_id());
        }
        Models.get(SiteModel.class).saveWrittenRefusingGone(site);
        if (settingsWritten) {
            SiteRegistrationTokens.saved(caller, Objects.requireNonNull(site.get(SiteModel.ID), "a stored site"),
                storedSettings, RawValues.mapOrNull(site.get(SiteModel.SETTINGS)), minted);
        }
        return null;
    }

    /**
     * The delegated edit: the three delegated columns and the manager's settings of the site's kind only, never the
     * admin normalizers. A tenant flipping the switch goes live through the save below, which the write-pipeline
     * enable invariant judges like every other writer; the kind is never written, and every setting its manager does
     * not change keeps its stored value (TenantWrites refuses any other change of the settings, whoever writes them).
     */
    private static @Nullable Void updateDelegated(@NonNull OperationCall<Row, SiteWrites.ManageInput> call) {
        Row site = call.subject();
        requireReviewedRevision(site, call.expectedVersion());
        SiteWrites.ManageInput input = Objects.requireNonNull(call.input(), "an edit has its input");
        Set<String> written = call.patched();
        if (writes(written, SiteModel.NAME)) {
            site.set(SiteModel.NAME, requiredName(input.name()));
        }
        if (writes(written, SiteModel.ENABLED)) {
            site.set(SiteModel.ENABLED, input.enabled());
        }
        if (writes(written, SiteModel.DESCRIPTION)) {
            site.set(SiteModel.DESCRIPTION, input.description());
        }
        // No settings posted (a kind whose manager changes none draws none): nothing to write.
        if (writes(written, SiteModel.SETTINGS) && input.settings() != null) {
            site.set(SiteModel.SETTINGS, SiteManagerSettings.merged(site.get(SiteModel.UPSTREAM_KIND),
                RawValues.map(site.get(SiteModel.SETTINGS)), input.settings()));
        }
        Models.get(SiteModel.class).saveWrittenRefusingGone(site);
        return null;
    }

    /**
     * Whether an edit writes the column: a full input writes every one, a patch only what it submitted.
     *
     * AIDEV-NOTE: a patch's input carries the stored value for every entry it did not submit, and writing that value
     * back is not a no-op for a column whose stored form differs from its posted form (the settings map, re-read
     * under its kind): a one-cell rename must leave every sibling exactly as stored (PartialWriteContractTest).
     */
    private static boolean writes(@Nullable Set<String> patched, @NonNull Field<?, ?> column) {
        return patched == null || patched.contains(column.getName());
    }

    /** @throws Violations on the name when it is blank: a site is always called something */
    private static @NonNull String requiredName(@Nullable String posted) {
        String name = Texts.trimmedOrNull(posted);
        if (name == null) {
            throw Violations.ofField(SiteModel.NAME.getName(), "", HohenheimMicrocopy.VIOLATIONS.of("name_required"));
        }
        return name;
    }

    /** @return the version an edit of the site reviews: its latest revision number (decided, no migration) */
    static long revisionOf(@NonNull Row site) {
        return SiteModel.REVISIONABLE.latestRevisionOf(Models.get(SiteModel.class), site.get(SiteModel.ID));
    }

    /** A programmatic caller reviewed nothing (null); a form's reviewed revision must still be the latest one. */
    private static void requireReviewedRevision(@NonNull Row site, @Nullable Long reviewed) {
        if (reviewed != null && reviewed != revisionOf(site)) {
            throw new DomainRefusal(ZenitRefusalReason.STALE, "site " + site.get(SiteModel.ID)
                + " moved past revision " + reviewed);
        }
    }

    /**
     * The settings a write stores: a sealed secret read back as its text, a blank secret leaf kept as stored, and a
     * dev-namespace site's map made even when nothing was posted, for its registration token
     * ({@link SiteRegistrationTokens#mintInto}).
     *
     * AIDEV-NOTE: the restore reads the STORED row (null on a create), so a blank token on an edit keeps the minted
     * one instead of minting a second; only an absent token is minted, exactly as the row lane did.
     *
     * @param stored the site as stored, null on a create
     */
    private static @Nullable Map<String, Object> settingsOf(@Nullable String kind, @Nullable Map<String, Object> posted,
                                                            @Nullable Row stored) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SiteModel.UPSTREAM_KIND.getName(), kind);
        values.put(SiteModel.SETTINGS.getName(), posted == null ? Map.of() : posted);
        Map<String, Object> existing = new LinkedHashMap<>();
        if (stored != null) {
            existing.put(SiteModel.UPSTREAM_KIND.getName(), stored.get(SiteModel.UPSTREAM_KIND));
            existing.put(SiteModel.SETTINGS.getName(), stored.get(SiteModel.SETTINGS));
        }
        // The form names a settings map by its entries (the fields' input names); the site stores the stored names.
        Map<String, Object> plain = SubmittedValueCoercion.coerceJsonOrThrow(SiteWrites.SETTINGS_FORM,
            SiteWrites.SETTINGS_FORM.entryMaps(values), null);
        Object restored = SiteWrites.SETTINGS_FORM.storedMaps(FormSecrets.restore(SiteWrites.SETTINGS_FORM, plain,
            SiteWrites.SETTINGS_FORM.byEntryNames(existing))).get(SiteModel.SETTINGS.getName());
        Map<String, Object> restoredMap = RawValues.mapOrNull(restored);
        Map<String, Object> settings = restoredMap != null ? new LinkedHashMap<>(restoredMap) : null;
        if (!DevNamespaceUpstreamKind.ID.toString().equals(kind)) {
            return posted == null ? null : settings;
        }
        return settings != null ? settings : new LinkedHashMap<>();
    }

    /**
     * Soft delete: reclaim the previews this site routed, then trash the record through the model's delete
     * (SiteModel.SOFT_DELETE stamps deleted_at and records the delete).
     *
     * AIDEV-NOTE: a site delete drops a HOSTNAME and nothing else: the site owns no runtime, so the application it
     * exposed keeps running. Previews are the one cascade left, and only the ones whose generated hostname lived on
     * THIS site: the soft delete fires no remove hook that could ever reclaim them.
     *
     * @return the number of sites deleted
     */
    private static @NonNull Integer deleteSite(@NonNull Row site) {
        PreviewDeployments.destroyForSite(site.get(SiteModel.ID));
        return Models.get(SiteModel.class).delete(site) ? 1 : 0;
    }
}
