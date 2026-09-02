package be.elevenways.hohenheim;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceLogModel;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.ActivitySources;
import be.elevenways.zenit.common.ZenitModule;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityPolicy;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.security.Permission;

/**
 * Record sources for the Hohenheim models plus the app stylesheet
 * registration. A {@link ZenitModule} (not class-load wiring) because
 * {@code RecordSource.of} resolves model singletons, which only exist after
 * the MODELS boot stage.
 */
public final class HohenheimSources implements ZenitModule {

    /**
     * The operator gate every installation-wide source rides. Kept as a common
     * constant (the server-side HohenheimPanel.ACCESS is the same string) so
     * common-registered sources can declare it without a server import.
     */
    public static final Permission ADMIN_ACCESS = Permission.of("hohenheim.admin.access");

    /**
     * The delegated /manage eligibility gate, kept as a common constant for the same
     * reason as {@link #ADMIN_ACCESS}: common-declared endpoints (the manage-panel POST
     * lanes) must name it without a server import. The server-side ManagePanel.ACCESS
     * aliases this so the two faces can never spell it differently.
     */
    public static final Permission MANAGE_ACCESS = Permission.of("hohenheim.manage.access");

    /**
     * Managing a host's install media: publishing ISOs onto its storage and removing
     * them again.
     *
     * AIDEV-NOTE: deliberately its OWN permission rather than a use of ADMIN_ACCESS, and
     * deliberately WITHOUT an {@code isAdmin(ctx) ||} bypass beside it (the shape
     * canCreateInstances uses). An ISO is arbitrary bootable code and the fetch makes
     * the controller an outbound HTTP client of whatever origin it is pointed at, so
     * "some admins may, some may not" is the whole point -- an admin bypass would make
     * this permission unable to say no to anyone. The bootstrap operator holds the "*"
     * grant and is therefore unaffected.
     */
    public static final Permission MEDIA_MANAGE = Permission.of("hohenheim.media.manage");

    public static final Identifier SPAMSERVICE_SYSTEM_USERS =
        Identifier.of("hohenheim", "spamservice_system_users");

    private static volatile boolean registered = false;

    @Override
    public void init() {
        register();
    }

    /**
     * Idempotent registration; test bootstraps that skip module discovery
     * call this directly after registering the models.
     */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        // The SiteModel default source is registered server-side (ManagePanel):
        // its per-principal scope reads zenit-auth record grants, which common
        // code cannot see, and /manage tenants must reach it too.

        // The internal ACME account row is bookkeeping holding the installation's ACME
        // account KEY, never a pickable certificate.
        //
        // AIDEV-NOTE: baseCriteria is the RIGHT knob here and is NOT merely cosmetic --
        // RecordSource ANDs it into every query, resolve, exists and bucket path, exactly
        // where accessCriteria lands. Restating the same exclusion as an accessCriteria was
        // tried and REVERTED: it changed no observable behaviour on any path, so it could
        // not be given a counterfactual, and an unfalsifiable second declaration is the
        // security theater this codebase keeps finding rather than the defence in depth it
        // looks like. accessCriteria is for decisions that differ PER PRINCIPAL; this one
        // does not. (Registration-wise the two are not interchangeable either: only
        // permission/accessCriteria/openTo* count as the access DECLARATION the registry
        // refuses a source without, and this source declares permission.)
        // AIDEV-NOTE: the tenant NARROWING is per-principal and so genuinely needs
        // accessCriteria -- and it cannot be spelled here, because it reads zenit-auth record
        // grants (server-only) while this class also feeds the BROWSER registry. It LANDED as
        // a server-side override in ManagePanel.registerSiteSource, beside the site and
        // domain ones, when the CertificateModel `view` capability shipped. This registration
        // is what the BROWSER registry keeps -- deliberately the narrower, admin-gated view;
        // registry membership is a server-authoritative question.
        RecordSourceRegistry.INSTANCE.register(RecordSource.of(CertificateModel.class)
            .search(CertificateModel.NICE_NAME)
            .baseCriteria(HohenheimSources::notTheAcmeAccountRow)
            .permission(ADMIN_ACCESS)
            .build());

        RecordSourceRegistry.INSTANCE.register(RecordSource.of(AccessListModel.class)
            .search(AccessListModel.NAME)
            .permission(ADMIN_ACCESS)
            .build());

        // Feeds the site-database attachment picker.
        RecordSourceRegistry.INSTANCE.register(RecordSource.of(DatabaseModel.class)
            .search(DatabaseModel.NAME)
            .permission(ADMIN_ACCESS)
            .build());

        // No explicit source for DatabaseEngineModel: the shared-engine pick on a
        // database's create form and the engine column ride the zenit-cms-derived default
        // of DatabaseEngineResource (its declared NAME search, the admin permission, the
        // edit link and inline create). An explicit copy here replaced that default
        // WITHOUT the edit/inline-create facets (source_capability_dropped at boot).

        // No explicit source for SiteAuthProviderModel or DnsZoneModel either: their
        // explicit copies added nothing over the derived defaults (AuthProviderResource and
        // DnsZoneResource declare the same search fields) and only cost the edit link.
        // Bans, hosts and runtime images DO need a projection / subtitle / sortable the
        // derived default lacks; they are declared server-side in AdminSources, where the
        // edit link and inline create can be spelled beside those facets.

        RecordSourceRegistry.INSTANCE.register(RecordSource.of(SystemUserModel.class)
            .search(SystemUserModel.NAME)
            .permission(ADMIN_ACCESS)
            .build());

        RecordSourceRegistry.INSTANCE.register(RecordSource.of(SystemUserModel.class)
            .id(SPAMSERVICE_SYSTEM_USERS)
            .search(SystemUserModel.NAME)
            .baseCriteria(() -> new CompositeCriteria(CompositeOperator.AND,
                SystemUserModel.OBSOLETE.eq(false),
                SystemUserModel.NAME.eq("spamservice"),
                SystemUserModel.UID.gt(0)))
            .permission(ADMIN_ACCESS)
            .build());

        // The admin dashboard's recent-activity records widget: the shared
        // zenit-cms factory registers "zenit.activity" over the /admin panel.
        ActivitySources.register("admin", ADMIN_ACCESS);

        // Sites carry field-level deltas in the activity log (the CMS history
        // tab renders them); every other model stays on the default tier.
        ActivityLog.setPolicy(SiteModel.MODEL_ID, ActivityPolicy.ALL);

        // Bookkeeping tables whose writes are machine-generated churn, not user
        // actions: instance logs upsert per console episode, and a release operation is
        // itself the history UI. Tracking them would flood zenit_activity.
        ActivityLog.setPolicy(InstanceLogModel.MODEL_ID, ActivityPolicy.NONE);
        ActivityLog.setPolicy(ReleaseOperationModel.MODEL_ID, ActivityPolicy.NONE);

        // Reconcile findings are a DERIVED CACHE, not a record of anything: every sweep
        // deletes a host's whole finding set and re-inserts it. Logging that churn buried
        // the one activity row that matters -- the operator's own orphan removal, which
        // OrphanActions now records explicitly on the host record.
        ActivityLog.setPolicy(ReconcileFindingModel.MODEL_ID, ActivityPolicy.NONE);
    }

    /**
     * Everything except the internal ACME account row (whose provider marks it); public so
     * the server-side scoped override reuses this exclusion instead of restating it.
     */
    public static CompositeCriteria notTheAcmeAccountRow() {
        return new CompositeCriteria(CompositeOperator.OR,
            CertificateModel.PROVIDER.isNull(),
            CertificateModel.PROVIDER.ne(CertificateModel.PROVIDER_ACME_ACCOUNT));
    }
}
