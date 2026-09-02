package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRecordLinks;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.server.page.CmsRecordSources;
import be.elevenways.zenit.common.data.RecordCreateProvider;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Permission;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The admin-gated record sources that need MORE than the zenit-cms-derived default
 * (a projection the dependent pickers narrow on, a sortable bucket field, a subtitle),
 * declared server-side so they can ALSO carry the two facets the derived default has and
 * an explicit replacement otherwise drops: the row's edit link and inline create.
 *
 * AIDEV-NOTE: these used to live in the common {@link HohenheimSources}, where neither
 * facet can be spelled (the edit link and the create provider are zenit-cms server
 * types), so every boot slogged {@code source_capability_dropped} for each of them and
 * every picker over them lost its edit link. Replacement is complete and never a merge,
 * so the facets must be declared HERE, on the explicit source; the browser registry does
 * not need these entries (registry membership is a server-authoritative question, and the
 * dependent pick rules carry their own mapping). A source whose explicit copy added
 * nothing over the derived default (dns_zone, site_auth_provider) is simply not declared:
 * the derived default IS the source.
 */
public final class AdminSources {

    private static volatile boolean registered = false;

    private AdminSources() {}

    /** Idempotent, exactly like {@link ManagePanel#registerSiteSource}. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        declare();
    }

    /** The registration body, callable again so a test can replay it against a fresh registry. */
    static void declare() {
        // Bans: feeds the active-bans stat tile (rules on `active`) and any bans-created
        // chart (sortable doubles as the bucketable whitelist for created_at).
        RecordSourceRegistry.INSTANCE.register(complete(RecordSource.of(BanModel.class)
            .project(BanModel.IP, BanModel.SOURCE, BanModel.ACTIVE,
                BanModel.EXPIRES_AT, BanModel.CREATED_AT)
            .sortable(BanModel.CREATED_AT), BanModel.class, new BanResource()));

        // Hosts, for the instance form's DEPENDENT host pick: the projection is the rule
        // vocabulary, so runtime and volume_backend MUST be projected -- the resolver
        // (HohenheimPickRules.KindHostRules) narrows on exactly those two.
        RecordSourceRegistry.INSTANCE.register(complete(RecordSource.of(ServerModel.class)
            .project(ServerModel.NAME, ServerModel.RUNTIME, ServerModel.VOLUME_BACKEND)
            .search(ServerModel.NAME), ServerModel.class, new ServerResource()));

        // Runtime images ("yolks"), for the instance form's dependent image pick: enabled
        // and incus_image are the resolver's rule vocabulary (HohenheimPickRules.RuntimeImageRules).
        RecordSourceRegistry.INSTANCE.register(complete(RecordSource.of(RuntimeImageModel.class)
            .project(RuntimeImageModel.NAME, RuntimeImageModel.DESCRIPTION,
                RuntimeImageModel.ENABLED, RuntimeImageModel.INCUS_IMAGE)
            .search(RuntimeImageModel.NAME)
            .subtitle(row -> {
                Object description = row.get(RuntimeImageModel.DESCRIPTION);
                return description != null ? String.valueOf(description) : "";
            }), RuntimeImageModel.class, new RuntimeImageResource()));
    }

    /**
     * The admin gate plus the two facets the derived default would have carried: the
     * detail-page edit link and, for a resource that can be created inline, the same
     * resource-backed create provider zenit-cms derives.
     */
    private static <M extends Model> @NonNull RecordSource<M> complete(RecordSource.@NonNull Builder<M> builder,
                                                                      @NonNull Class<M> modelClass,
                                                                      @NonNull RowResource resource) {
        M model = Models.get(modelClass);
        Identifier modelId = model.getModelId();
        String primaryKey = model.getPrimaryKeyField().getName();
        builder.permission(HohenheimSources.ADMIN_ACCESS)
            .editUrl((Row row) -> CmsRecordLinks.detailUrl(modelId, String.valueOf(row.get(primaryKey))));

        RecordCreateProvider create = CmsRecordSources.createProviderFor(resource);
        if (create != null) {
            Permission createPermission = resource.createPermission();
            if (createPermission != null) {
                builder.creatable(create, createPermission);
            } else {
                builder.creatable(create);
            }
        }
        return builder.build();
    }
}
