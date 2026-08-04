package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * Game-domain mappings: domain record -> backend instance through a Velocity proxy.
 * Every write routes through {@link GameDomains}, the funnel that enforces authority
 * over BOTH records and materializes forced-hosts config plus DNS output on change.
 */
public final class GameDomainResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(GameDomainModel.SITE_DOMAIN_ID, SiteDomainModel.MODEL_ID).build())
        .add(RelationPick.of(GameDomainModel.BACKEND_INSTANCE_ID, InstanceModel.MODEL_ID).build())
        .add(RelationPick.of(GameDomainModel.PROXY_INSTANCE_ID, InstanceModel.MODEL_ID).build())
        .add(GameDomainModel.BACKEND_PORT)
        .add(GameDomainModel.ENABLED)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(GameDomainModel.SITE_DOMAIN_ID)
            .relation(RelationPick.of(GameDomainModel.SITE_DOMAIN_ID,
                SiteDomainModel.MODEL_ID).build())
            .build())
        .column(ColumnSpec.fromField(GameDomainModel.BACKEND_INSTANCE_ID)
            .relation(RelationPick.of(GameDomainModel.BACKEND_INSTANCE_ID,
                InstanceModel.MODEL_ID).build())
            .build())
        .column(ColumnSpec.fromField(GameDomainModel.PROXY_INSTANCE_ID)
            .relation(RelationPick.of(GameDomainModel.PROXY_INSTANCE_ID,
                InstanceModel.MODEL_ID).build())
            .build())
        .column(ColumnSpec.fromField(GameDomainModel.BACKEND_PORT).build())
        .column(ColumnSpec.fromField(GameDomainModel.ENABLED).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "game_domain"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "game_domain"); }
    @Override public @NonNull String slug() { return "game-domains"; }
    @Override public @NonNull Model model() { return Models.get(GameDomainModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 21; }
    @Override public @NonNull Icon icon() { return Icon.of("gamepad"); }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Row row = valuesToRow(coerced);
        GameDomains.applyAuthorized(accessContext, row);
        return row.get(GameDomainModel.ID);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        applyValuesToRow(existing, coerced);
        GameDomains.applyAuthorized(accessContext, existing);
    }

    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer id = existing.get(GameDomainModel.ID);
        if (id != null) {
            GameDomains.deleteAuthorized(accessContext, id);
        }
    }
}
