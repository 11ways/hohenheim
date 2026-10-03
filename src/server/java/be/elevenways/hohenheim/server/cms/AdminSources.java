package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.server.page.CmsRecordSources;
import be.elevenways.zenit.cms.server.panel.ResourceVerbs;
import be.elevenways.zenit.common.data.RecordCreateProvider;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Permission;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

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
 * nothing over the derived default (dns_zone) is simply not declared: the derived default
 * IS the source. A model whose admin entry is a PanelResource gets its model-level source from
 * zenit-cms as well (CmsRecordSources: the entry's gate, search, edit link and inline create).
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
        // chart (sortable doubles as the bucketable whitelist for created_at). BanParts is a
        // panel resource, so this is the model's own source; no inline create (no pick offers
        // a ban: the manual ban is the entry's quick-add bar).
        RecordSourceRegistry.INSTANCE.register(admin(RecordSource.of(BanModel.class)
            .project(BanModel.IP, BanModel.SOURCE, BanModel.ACTIVE,
                BanModel.EXPIRES_AT, BanModel.CREATED_AT)
            .sortable(BanModel.CREATED_AT), BanModel.class).build());

        // Backup targets, for the instance form's target pick: BackupTargetParts is a panel resource, whose one
        // source is panel-qualified, so the model's own source is this one, creatable through the admin entry's
        // own create form exactly as the derived default was. Absent with the instance role.
        Panel adminPanel = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN),
            "the admin panel is registered before its sources");
        if (adminPanel.entryBySlug(BackupTargetParts.SLUG) instanceof PanelResource<?> targets) {
            RecordSourceRegistry.INSTANCE.register(complete(RecordSource.of(BackupTargetModel.class)
                .search(BackupTargetModel.NAME), BackupTargetModel.class,
                adminPanel, targets));
        }

        // Hosts, for the instance form's DEPENDENT host pick: the projection is the rule
        // vocabulary, so runtime and volume_backend MUST be projected -- the resolver
        // (HohenheimPickRules.KindHostRules) narrows on exactly those two.
        RecordSourceRegistry.INSTANCE.register(complete(RecordSource.of(ServerModel.class)
            .project(ServerModel.NAME, ServerModel.RUNTIME, ServerModel.VOLUME_BACKEND)
            .search(ServerModel.NAME), ServerModel.class, adminPanel, ServerParts.admin()));

        // Runtime images ("yolks"), for the instance form's dependent image pick: enabled
        // and incus_image are the resolver's rule vocabulary (HohenheimPickRules.RuntimeImageRules).
        RecordSourceRegistry.INSTANCE.register(complete(RecordSource.of(RuntimeImageModel.class)
            .project(RuntimeImageModel.NAME, RuntimeImageModel.DESCRIPTION,
                RuntimeImageModel.ENABLED, RuntimeImageModel.INCUS_IMAGE)
            .search(RuntimeImageModel.NAME)
            .subtitle(row -> {
                Object description = row.get(RuntimeImageModel.DESCRIPTION);
                return description != null ? String.valueOf(description) : "";
            }), RuntimeImageModel.class, adminPanel, RuntimeImageParts.admin()));

    }

    /**
     * The admin gate plus the two facets the derived default would have carried: the
     * detail-page edit link and, for a resource that can be created inline, the same
     * resource-backed create provider zenit-cms derives.
     */
    private static <M extends Model> @NonNull RecordSource<M> complete(RecordSource.@NonNull Builder<M> builder,
                                                                      @NonNull Class<M> modelClass,
                                                                      @NonNull Panel panel,
                                                                      @NonNull PanelResource<?> resource) {
        admin(builder, modelClass);
        RecordCreateProvider create = CmsRecordSources.createProviderFor(panel, resource);
        if (create != null) {
            Permission createPermission = ResourceVerbs.permission(resource, ResourceVerb.CREATE);
            if (createPermission != null) {
                builder.creatable(create, createPermission);
            } else {
                builder.creatable(create);
            }
        }
        return builder.build();
    }

    /** The admin gate and the detail-page edit link. */
    private static <M extends Model> RecordSource.@NonNull Builder<M> admin(RecordSource.@NonNull Builder<M> builder,
                                                                            @NonNull Class<M> modelClass) {
        M model = Models.get(modelClass);
        Identifier modelId = model.getModelId();
        String primaryKey = model.getPrimaryKeyField().getName();
        return builder.permission(HohenheimSources.ADMIN_ACCESS)
            .editUrl((Row row) -> AdminRecordLinks.detailUrl(modelId, String.valueOf(row.get(primaryKey))));
    }
}
