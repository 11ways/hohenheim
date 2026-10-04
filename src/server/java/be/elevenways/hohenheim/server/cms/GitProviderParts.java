package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.source.GitProviderOperations;
import be.elevenways.hohenheim.source.GitProviderOperations.ConnectionTest;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The git provider entries' shared parts, and the admin provider resource and its /manage twin built from them.
 *
 * AIDEV-NOTE: credentials are static encrypted secret columns, and the connection test lists repositories through
 * the real client, so a wrong token or unusable App key fails HERE, not on the first deploy. What the /manage twin
 * drops, and why: SHARED (the operator's declaration that a credential serves every tenant) is absent from its form
 * and table, the model-level freeze in TenantWrites being the gate every writer answers to; its list is the providers
 * the tenant MANAGES, never the shared ones it may only USE; a failed connection test answers one generic sentence;
 * and the admin history tab stays off it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class GitProviderParts {

    private GitProviderParts() {
    }

    /** @return the admin provider resource: every provider, and the SHARED switch */
    public static @NonNull PanelResource<Row> admin() {
        FormSpec form = FormSpec.builder()
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
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(GitProviderModel.NAME).filterable().build())
            .column(ColumnSpec.fromField(GitProviderModel.KIND).filterable().build())
            .column(ColumnSpec.fromField(GitProviderModel.BASE_URL).copyable().build())
            .column(ColumnSpec.fromField(GitProviderModel.SHARED).filterable().build())
            .column(ColumnSpec.fromField(GitProviderModel.CREATED_AT).build())
            .build();
        return entry("git_provider", table, form, GitProviderParts::operatorWords)
            .writes(ResourceMutations.rows().create().update().delete().build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the /manage twin: the providers the caller registered, through the delegated form */
    public static @NonNull PanelResource<Row> manage() {
        FormSpec form = FormSpec.builder()
            .add(GitProviderModel.NAME)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(GitProviderModel.KIND))
            .add(GitProviderModel.BASE_URL)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(GitProviderModel.SETTINGS))
            .add(GitProviderModel.ACCESS_TOKEN)
            .add(GitProviderModel.APP_PRIVATE_KEY_PEM)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(GitProviderModel.NAME).build())
            .column(ColumnSpec.fromField(GitProviderModel.KIND).filterable().build())
            .column(ColumnSpec.fromField(GitProviderModel.BASE_URL).copyable().build())
            .build();
        return entry("manage_git_provider", table, form, GitProviderParts::tenantWords)
            // Admins see every provider; everyone else only the ones the walk confirms manage on, so an unowned id
            // reads as MISSING (zenit-cms 404s an out-of-scope load) rather than forbidden.
            .scope(TenantScopes.MANAGED_GIT_PROVIDERS)
            // NAV-ONLY (zero granted providers hide the empty list); the route stays scoped.
            .hasInScopeRecords(access -> HohenheimAccess.reachesAny(access, GitProviderModel.MODEL_ID,
                HohenheimAccess.MANAGE))
            .writes(ResourceMutations.rows().create().update().delete()
                .beforeSave(save -> {
                    if (save.isCreate()) {
                        refuseSharedChange(save.values());
                    }
                })
                // The grant IS the ownership, exactly as for a tenant's instance or database; an operator create
                // plants nothing (an empty subject set IS operator ownership). THE planting loop also drops the scope
                // memo the grant made stale, so the create's own scope check sees the new row.
                .afterSave(save -> {
                    if (save.isCreate()) {
                        HohenheimAccess.grantCreatorManage(GitProviderModel.MODEL_ID,
                            Integer.parseInt(String.valueOf(save.key())), save.access());
                    }
                })
                .build())
            // The contributed tabs only (the generic access matrix, which gates itself per record): the admin
            // activity and revision history stays off the delegated surface, which also 404s its routes.
            .tabs(ResourceTabs.<Row>none().withContributions())
            .build();
    }

    /** The entry, list, reads, inline cell and connection test both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull String id, @NonNull TableSpec<Row> table,
                                                             @NonNull FormSpec form,
                                                             @NonNull Function<ConnectionTest, CmsActionResult> words) {
        return PanelResource.builder(HohenheimIds.id(id), HohenheimSlugs.GIT_PROVIDERS, GitProviderOperations.PROVIDER)
            .label(Microcopy.of("plural").withFilter("scope", "git_provider"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "git_provider"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "git_provider"))
            .icon(Icon.of("code-branch"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(70)
            .reads(ResourceReads.rows())
            // Name and endpoint; the credentials are secret and never enter a search.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL)
                .search(GitProviderModel.NAME, GitProviderModel.BASE_URL).build())
            // The name only. BASE_URL is WHERE the stored access token gets sent, so retyping it in a cell redirects
            // a live secret to another host; KIND selects the AUTH SCHEME, so changing it re-interprets which stored
            // credential columns are read. Everything else on the record is secret.
            .form(ResourceForm.<Row>of(form).inlineEditable(GitProviderModel.NAME).build())
            .actions(List.of(PanelAction.<Row, ConnectionTest>places(GitProviderOperations.TEST_CONNECTION,
                    ActionPlacement.ROW, (request, result) -> words.apply(result.value()))
                .label(Microcopy.of("test_connection").withFilter("scope", "git_provider"))
                .icon(Icon.of("plug-circle-check"))
                .build()));
    }

    /** The operator chose the URL and owns the network it probes, so a failure names the client's own reason. */
    static @NonNull CmsActionResult operatorWords(@NonNull ConnectionTest test) {
        if (test.failure() != null) {
            return CmsActionResult.errorToast(Microcopy.of("test_failed").withFilter("scope", "git_provider")
                .withArg("reason", test.failure()));
        }
        return passed(test);
    }

    /**
     * ONE generic sentence for every failed test on the tenant surface.
     *
     * AIDEV-NOTE: the tenant chose the URL this probe connects to, so echoing the client's own failure ("connection
     * refused", "timed out", a TLS or DNS error) would turn the button into a port scanner of whatever the server can
     * reach, with the toast as its oracle. The real reason is logged by the handler.
     */
    static @NonNull CmsActionResult tenantWords(@NonNull ConnectionTest test) {
        if (test.failure() != null) {
            return CmsActionResult.errorToast(Microcopy.of("test_failed_generic").withFilter("scope", "git_provider"));
        }
        return passed(test);
    }

    private static @NonNull CmsActionResult passed(@NonNull ConnectionTest test) {
        return CmsActionResult.toast(Microcopy.of("test_ok").withFilter("scope", "git_provider")
            .withArg("count", test.repositories()));
    }

    /**
     * Refuse a /manage create that would move SHARED off its default.
     *
     * AIDEV-NOTE: the form omits SHARED and coercion only carries form entries, so this is the twin's EARLY refusal
     * for a wider map. An update needs none: the twin's row writer applies its form entries only, so SHARED never
     * reaches the row. The gate every writer answers to is the model-level freeze in TenantWrites
     * (checkGitProviderWrite), which refuses with the same field and words, beside the identical AccessListModel.SHARED
     * freeze.
     *
     * @throws Violations anchored on the shared field
     */
    static void refuseSharedChange(@NonNull Map<String, Object> coerced) {
        String name = GitProviderModel.SHARED.getName();
        if (coerced.containsKey(name) && Boolean.TRUE.equals(coerced.get(name))
                != Boolean.TRUE.equals(GitProviderModel.SHARED.getDefaultValue())) {
            throw Violations.ofField(name, coerced.get(name), CmsSupport.violationText("tenant_field_frozen"));
        }
    }
}
