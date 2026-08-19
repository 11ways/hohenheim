package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.source.GitProviders;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin surface for git provider installations. Credentials are static encrypted
 * secret columns; the connection test lists repositories through the real client, so
 * a wrong token or unusable App key fails HERE, not on the first deploy.
 */
public final class GitProviderResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(GitProviderModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(GitProviderModel.KIND))
        .add(GitProviderModel.BASE_URL)
        .add(GitProviderModel.ACCESS_TOKEN)
        .add(GitProviderModel.APP_ID)
        .add(GitProviderModel.APP_INSTALLATION_ID)
        .add(GitProviderModel.APP_PRIVATE_KEY_PEM)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(GitProviderModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(GitProviderModel.KIND).filterable().build())
        .column(ColumnSpec.fromField(GitProviderModel.BASE_URL).copyable().build())
        .column(ColumnSpec.fromField(GitProviderModel.CREATED_AT).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "git_provider"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "git_provider"); }
    @Override public @NonNull String slug() { return "git-providers"; }
    @Override public @NonNull Model model() { return Models.get(GitProviderModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /** Name and endpoint; the credentials are secret and never enter a search. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(GitProviderModel.NAME, GitProviderModel.BASE_URL);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 70; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "git_provider");
    }
    @Override public @NonNull Icon icon() { return Icon.of("code-branch"); }

    /**
     * The name only.
     *
     * AIDEV-NOTE: BASE_URL is excluded and that is a credential decision, not a tidiness
     * one -- it is WHERE the stored access token gets sent, so retyping it in a cell
     * redirects a live secret to another host. KIND is excluded because it selects the
     * AUTH SCHEME (token versus App key), so changing it re-interprets which stored
     * credential columns are even read. Everything else on the record is secret.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(GitProviderModel.NAME);
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "test_git_provider"))
            .label(Microcopy.of("test_connection").withFilter("scope", "git_provider"))
            .icon(Icon.of("plug-circle-check"))
            .handler((row, ctx) -> {
                try {
                    int count = GitProviders.clientFor(row).listRepositories().size();
                    return CmsActionResult.toast(
                        Microcopy.of("test_ok").withFilter("scope", "git_provider")
                            .withArg("count", count));
                } catch (Exception unhealthy) {
                    return CmsActionResult.errorToast(
                        Microcopy.of("test_failed").withFilter("scope", "git_provider")
                            .withArg("reason", unhealthy.getMessage() != null
                                ? unhealthy.getMessage() : unhealthy.toString()));
                }
            })
            .build());
        return actions;
    }
}
