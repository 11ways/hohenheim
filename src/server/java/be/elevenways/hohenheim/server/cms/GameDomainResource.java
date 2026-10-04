package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.game.GameDomainOperations;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * The game-domain mapping entry's parts (domain record to backend instance through a Velocity proxy), and the admin
 * entry built from them; every write is a {@link GameDomainOperations} operation over the GameDomains funnel.
 *
 * AIDEV-NOTE: still named GameDomainResource because the instance list names {@link #SLUG} here as a related page,
 * and that file is not this slice's to edit; it is a parts holder, never instantiated.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class GameDomainResource {

    /** This entry's slug, which the instance list names as a related page. */
    public static final String SLUG = "game-domains";

    private GameDomainResource() {
    }

    /** @return the admin mapping entry, demoted out of the sidebar */
    public static @NonNull PanelResource<Row> admin() {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(GameDomainModel.SITE_DOMAIN_ID)
                .relation(RelationPick.of(GameDomainModel.SITE_DOMAIN_ID, SiteDomainModel.MODEL_ID).build())
                .build())
            .column(ColumnSpec.fromField(GameDomainModel.BACKEND_INSTANCE_ID)
                .relation(RelationPick.of(GameDomainModel.BACKEND_INSTANCE_ID, InstanceModel.MODEL_ID).build())
                .build())
            .column(ColumnSpec.fromField(GameDomainModel.PROXY_INSTANCE_ID)
                .relation(RelationPick.of(GameDomainModel.PROXY_INSTANCE_ID, InstanceModel.MODEL_ID).build())
                .build())
            .column(ColumnSpec.fromField(GameDomainModel.BACKEND_PORT).build())
            .column(ColumnSpec.fromField(GameDomainModel.ENABLED).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("game_domain"), SLUG, GameDomainOperations.MAPPING)
            .label(Microcopy.of("plural").withFilter("scope", "game_domain"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "game_domain"))
            // Demoted out of the sidebar, so this sentence reaches a reader through the panel index and the
            // related-pages menu of the list that names it.
            .description(CmsSupport.navHint("game_domain"))
            .icon(Icon.of("gamepad"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(21)
            .showInNav(false)
            .reads(ResourceReads.rows().title(GameDomainResource::hostname))
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).build())
            .form(ResourceForm.<Row>of(GameDomainOperations.FORM).build())
            .writes(ResourceMutations.rows().create(GameDomainOperations.CREATE)
                .update(GameDomainOperations.UPDATE, mapping -> Objects.requireNonNull(
                    mapping.get(GameDomainModel.VERSION), "a locked mapping carries its version").longValue())
                .delete(GameDomainOperations.DELETE)
                .build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * A mapping has no name of its own: it IS the hostname it accelerates, the site domain it points at.
     *
     * @return that hostname, or null to fall back to the generic title
     */
    private static @Nullable String hostname(@NonNull Row mapping) {
        Integer domainId = mapping.get(GameDomainModel.SITE_DOMAIN_ID);
        Row domain = domainId != null ? Models.get(SiteDomainModel.class).findById(domainId) : null;
        String hostname = domain != null ? domain.get(SiteDomainModel.HOSTNAME) : null;
        return hostname != null && !hostname.isBlank() ? hostname : null;
    }
}
