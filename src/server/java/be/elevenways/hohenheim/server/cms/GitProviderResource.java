package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.source.GitProviders;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
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
import java.util.Map;

/**
 * Admin surface for git provider installations. Credentials are static encrypted
 * secret columns; the connection test lists repositories through the real client, so
 * a wrong token or unusable App key fails HERE, not on the first deploy. This surface
 * owns the SHARED switch -- the delegated projection ({@link ManageGitProviderResource})
 * deliberately does not.
 */
public class GitProviderResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(GitProviderModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(GitProviderModel.KIND))
        .add(GitProviderModel.BASE_URL)
        // The per-kind sub-form: the GitHub App identifiers appear on a GitHub provider
        // and on no other, without a second resource or a hand-written visibility rule.
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(GitProviderModel.SETTINGS))
        .add(GitProviderModel.ACCESS_TOKEN)
        .add(GitProviderModel.APP_PRIVATE_KEY_PEM)
        .add(GitProviderModel.SHARED)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(GitProviderModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(GitProviderModel.KIND).filterable().build())
        .column(ColumnSpec.fromField(GitProviderModel.BASE_URL).copyable().build())
        .column(ColumnSpec.fromField(GitProviderModel.SHARED).filterable().build())
        .column(ColumnSpec.fromField(GitProviderModel.CREATED_AT).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "git_provider"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "git_provider"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "git_provider"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.GIT_PROVIDERS; }
    @Override public @NonNull Model model() { return Models.get(GitProviderModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

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
            .handler((row, ctx) -> this.testConnection(row))
            .build());
        return actions;
    }

    /**
     * List the provider's repositories through the real client and word the outcome.
     *
     * AIDEV-NOTE: the probe is an OUTBOUND request to a URL the record's author chose, and on
     * /manage that author is a tenant -- so it rides THE provider client
     * ({@code GitProviders.clientFor(row)}), whose outbound guard is decided by the ROW's
     * ownership (SourceOwnership.providerGuard: a tenant-owned provider reaches public
     * addresses only, on either panel), never a request built here.
     */
    final @NonNull CmsActionResult testConnection(@NonNull Row row) {
        try {
            int count = GitProviders.clientFor(row).listRepositories().size();
            return CmsActionResult.toast(
                Microcopy.of("test_ok").withFilter("scope", "git_provider")
                    .withArg("count", count));
        } catch (Exception unhealthy) {
            Blast.slog("hohenheim.git_provider.test_failed", Map.of(
                "provider", String.valueOf(row.get(GitProviderModel.ID)),
                "reason", String.valueOf(unhealthy.getMessage() != null
                    ? unhealthy.getMessage() : unhealthy.toString())));
            return CmsActionResult.errorToast(this.connectionFailure(unhealthy));
        }
    }

    /**
     * The toast a failed connection test shows. The operator surface names the client's own
     * reason: the operator chose the URL and owns the network it probes.
     */
    protected @NonNull Microcopy connectionFailure(@NonNull Exception failure) {
        return Microcopy.of("test_failed").withFilter("scope", "git_provider")
            .withArg("reason", failure.getMessage() != null
                ? failure.getMessage() : failure.toString());
    }
}
