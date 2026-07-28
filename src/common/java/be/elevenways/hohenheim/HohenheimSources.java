package be.elevenways.hohenheim;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DeploymentModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
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

        // The internal ACME account row is bookkeeping, never a pickable certificate.
        RecordSourceRegistry.INSTANCE.register(RecordSource.of(CertificateModel.class)
            .search(CertificateModel.NICE_NAME)
            .baseCriteria(() -> new CompositeCriteria(CompositeOperator.OR,
                CertificateModel.PROVIDER.isNull(),
                CertificateModel.PROVIDER.ne(CertificateModel.PROVIDER_ACME_ACCOUNT)))
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

        RecordSourceRegistry.INSTANCE.register(RecordSource.of(SiteAuthProviderModel.class)
            .search(SiteAuthProviderModel.NAME)
            .permission(ADMIN_ACCESS)
            .build());

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

        // Feeds the DNS record form's zone picker.
        RecordSourceRegistry.INSTANCE.register(RecordSource.of(DnsZoneModel.class)
            .search(DnsZoneModel.ORIGIN)
            .permission(ADMIN_ACCESS)
            .build());

        // Bans: feeds the active-bans stat tile and the bans-created chart
        // (sortable doubles as the bucketable whitelist for created_at).
        RecordSourceRegistry.INSTANCE.register(RecordSource.of(BanModel.class)
            .project(BanModel.IP, BanModel.SOURCE, BanModel.ACTIVE,
                BanModel.EXPIRES_AT, BanModel.CREATED_AT)
            .sortable(BanModel.CREATED_AT)
            .permission(ADMIN_ACCESS)
            .build());

        // The admin dashboard's recent-activity records widget: the shared
        // zenit-cms factory registers "zenit.activity" over the /admin panel.
        ActivitySources.register("admin", ADMIN_ACCESS);

        // Sites carry field-level deltas in the activity log (the CMS history
        // tab renders them); every other model stays on the default tier.
        ActivityLog.setPolicy(SiteModel.MODEL_ID, ActivityPolicy.ALL);

        // Bookkeeping tables whose writes are machine-generated churn, not user
        // actions: proclogs flush every 30s per process and deployments carry
        // their own history UI. Tracking them would flood zenit_activity.
        ActivityLog.setPolicy(ProclogModel.MODEL_ID, ActivityPolicy.NONE);
        ActivityLog.setPolicy(DeploymentModel.MODEL_ID, ActivityPolicy.NONE);
    }
}
