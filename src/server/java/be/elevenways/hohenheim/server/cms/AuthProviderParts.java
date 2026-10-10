package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderTypeHandler;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectArity;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.RowDeleteOperations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The site auth providers' parts: the admin resource over the shared authentication declarations sites point at.
 *
 * AIDEV-NOTE: the delete is core's row delete under its own id {@link #DELETE}, so the "still in use" refusal is its
 * availability (offered dead, with the reason on screen, and asked again inside the delete) and never a check after
 * the click. Create and update keep the provider type's canonical config storage through custom row writers; the
 * update applies only what the write carries (a one-entry inline map renames without touching the config).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class AuthProviderParts {

    private static final SubjectType<Row> SUBJECT = SubjectType.record(SiteAuthProviderModel.MODEL_ID);

    /** Deletes a provider no site and no access rule names; offered dead while one does. */
    public static final Operation<Row, Void, Integer> DELETE =
        RowDeleteOperations.declare(SiteAuthProviderModel.class, SubjectArity.ONE,
                OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .id(HohenheimIds.id("delete_auth_provider"))
            .availability((provider, access) -> DeleteImpact.authProviderInUse(provider))
            .register();

    private AuthProviderParts() {
    }

    /** The list's "used by" column. */
    private static final String USED_BY_COLUMN = "used_by";

    /** How many places use a provider, in words: the rules naming it plus the sites it gates. */
    private static @NonNull Microcopy usedBy(@Nullable Integer providerId) {
        long uses = DeleteImpact.rulesNamingAuthProvider(providerId)
            + DeleteImpact.sitesGatedByAuthProvider(providerId).size();
        return uses == 0
            ? HohenheimMicrocopy.AUTH_PROVIDER.of("used_by_nothing")
            : HohenheimMicrocopy.AUTH_PROVIDER.of("used_by_count").withArg("count", uses);
    }

    /** @return the admin auth-provider resource */
    public static @NonNull PanelResource<Row> admin() {
        // The derived spec would render the type-discriminated CONFIG blob; these columns are what an operator
        // actually compares providers by.
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(SiteAuthProviderModel.NAME).filterable()
                .subtext("required_permission").build())
            .column(ColumnSpec.fromField(SiteAuthProviderModel.REQUIRED_PERMISSION).hidden().build())
            .column(ColumnSpec.fromField(SiteAuthProviderModel.PROVIDER_TYPE).filterable().build())
            // Where it is used, the Access board's "used by" line: access-list rules naming it plus sites it gates.
            .column(ColumnSpec.virtual(USED_BY_COLUMN, HohenheimMicrocopy.AUTH_PROVIDER.of("used_by_column")).build())
            .column(ColumnSpec.fromField(SiteAuthProviderModel.CREATED_AT).hidden().build())
            .filter(FilterSpec.leaf(SiteAuthProviderModel.NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(SiteAuthProviderModel.NAME)).build())
            .build();
        FormSpec form = FormSpec.builder()
            .add(SiteAuthProviderModel.NAME)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteAuthProviderModel.PROVIDER_TYPE))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteAuthProviderModel.CONFIG))
            .add(SiteAuthProviderModel.REQUIRED_PERMISSION)
            .build();
        return PanelResource.builder(HohenheimIds.id("auth_provider"), HohenheimSlugs.AUTH_PROVIDERS, SUBJECT)
            .label(HohenheimMicrocopy.AUTH_PROVIDER.of("plural"))
            .recordLabel(HohenheimMicrocopy.AUTH_PROVIDER.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.AUTH_PROVIDER))
            .icon(Icon.of("key"))
            .navGroup(HohenheimPanel.NETWORK_GROUP)
            .navOrder(50)
            // A member of the Access cluster (HohenheimPanel): the cluster stands in for it in the sidebar, and a
            // member hidden from nav would drop out of the cluster's tabs too.
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(SiteAuthProviderModel.NAME, SiteAuthProviderModel.REQUIRED_PERMISSION)
                .computed(Objects.requireNonNull(table.column(USED_BY_COLUMN)),
                    (provider, request) -> usedBy(provider.get(SiteAuthProviderModel.ID)))
                .build())
            .form(ResourceForm.<Row>of(form).build())
            .writes(ResourceMutations.rows()
                .create(call -> {
                    SiteAuthProviderModel providers = Models.get(SiteAuthProviderModel.class);
                    Row provider = providers.createEmptyRow();
                    normalized(call.values(), null).forEach(provider::set);
                    providers.save(provider);
                    return provider.get(SiteAuthProviderModel.ID);
                })
                .update(call -> {
                    Row provider = call.record();
                    normalized(call.values(), provider).forEach(provider::set);
                    Models.get(SiteAuthProviderModel.class).save(provider);
                    return null;
                })
                .delete(DELETE)
                .build())
            .deleteConfirmation(DeleteConfirmation.of(DeleteConfirmation.body(
                HohenheimMicrocopy.AUTH_PROVIDER.of("delete_confirm"))))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * The write's values with the config stored the way its provider type keeps it.
     *
     * AIDEV-NOTE: an unknown provider type MUST fail loudly: storing the submitted config as-is would bypass the
     * provider's canonical storage normalization. A write that carries no config (an inline rename) keeps the stored
     * one untouched.
     *
     * @param existing the stored provider, null on a create
     * @throws Violations on the provider type when no handler knows it
     */
    static @NonNull Map<String, Object> normalized(@NonNull Map<String, Object> values, @Nullable Row existing) {
        Map<String, Object> written = new LinkedHashMap<>(values);
        Object typeValue = CmsSupport.valueOf(written, existing, SiteAuthProviderModel.PROVIDER_TYPE);
        String providerType = typeValue != null ? String.valueOf(typeValue) : null;
        SiteAuthProviderTypeHandler handler = SiteAuthProviders.getHandler(providerType);
        if (handler == null) {
            throw Violations.ofField("provider_type", providerType,
                HohenheimMicrocopy.VIOLATIONS.of("unknown_provider_type").withArg("type", providerType));
        }
        if (existing != null && !written.containsKey(SiteAuthProviderModel.CONFIG.getName())) {
            return written;
        }
        Object rawConfig = written.get(SiteAuthProviderModel.CONFIG.getName());
        Map<String, Object> submitted = RawValues.map(rawConfig);
        Map<String, Object> existingConfig = existing != null
            ? RawValues.mapOrNull(existing.get(SiteAuthProviderModel.CONFIG)) : null;
        written.put(SiteAuthProviderModel.CONFIG.getName(), handler.normalizeConfigForSave(submitted, existingConfig));
        return written;
    }
}
