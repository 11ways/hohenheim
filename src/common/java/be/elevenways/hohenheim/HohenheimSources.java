package be.elevenways.hohenheim;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.ZenitModule;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.ui.Stylesheets;

/**
 * Record sources for the Hohenheim models plus the app stylesheet
 * registration. A {@link ZenitModule} (not class-load wiring) because
 * {@code RecordSource.of} resolves model singletons, which only exist after
 * the MODELS boot stage.
 */
public final class HohenheimSources implements ZenitModule {

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

        RecordSourceRegistry.INSTANCE.register(RecordSource.of(SiteModel.class)
            .search(SiteModel.NAME, SiteModel.SLUG)
            .baseCriteria(() -> SiteModel.DELETED_AT.isNull())
            .build());

        // The internal ACME account row is bookkeeping, never a pickable certificate.
        RecordSourceRegistry.INSTANCE.register(RecordSource.of(CertificateModel.class)
            .search(CertificateModel.NICE_NAME)
            .baseCriteria(() -> new CompositeCriteria(CompositeOperator.OR,
                CertificateModel.PROVIDER.isNull(),
                CertificateModel.PROVIDER.ne(CertificateModel.PROVIDER_ACME_ACCOUNT)))
            .build());

        RecordSourceRegistry.INSTANCE.register(RecordSource.of(AccessListModel.class)
            .search(AccessListModel.NAME)
            .build());

        RecordSourceRegistry.INSTANCE.register(RecordSource.of(SiteAuthProviderModel.class)
            .search(SiteAuthProviderModel.NAME)
            .build());

        // Feeds the admin dashboard's recent-activity records widget.
        RecordSourceRegistry.INSTANCE.register(RecordSource.of(ActivityModel.class)
            .project(ActivityModel.ACTION, ActivityModel.MODEL,
                ActivityModel.DETAIL, ActivityModel.ACTOR_LABEL, ActivityModel.CREATED_AT)
            .sortable(ActivityModel.CREATED_AT)
            .build());

        Stylesheets.register(Identifier.of("hohenheim", "app"), "/hohenheim.css", Stylesheets.WEIGHT_APP);
    }
}
