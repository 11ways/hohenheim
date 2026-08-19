package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderTypeHandler;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Per-site proxy auth providers with a type-discriminated config sub-form.
 * The save path routes the submitted config through the provider type's
 * normalizeConfigForSave, which owns the provider-specific storage shape;
 * an unknown type fails loudly instead of storing an undefined shape.
 */
public final class AuthProviderResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(SiteAuthProviderModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteAuthProviderModel.PROVIDER_TYPE))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteAuthProviderModel.CONFIG))
        .add(SiteAuthProviderModel.REQUIRED_PERMISSION)
        .build();

    // The derived spec would render the type-discriminated CONFIG blob; these three
    // columns are what an operator actually compares providers by.
    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(SiteAuthProviderModel.NAME).filterable()
            .subtext("required_permission").build())
        .column(ColumnSpec.fromField(SiteAuthProviderModel.REQUIRED_PERMISSION).hidden().build())
        .column(ColumnSpec.fromField(SiteAuthProviderModel.PROVIDER_TYPE).filterable().build())
        .column(ColumnSpec.fromField(SiteAuthProviderModel.CREATED_AT).build())
        .filter(FilterSpec.forField(SiteAuthProviderModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(SiteAuthProviderModel.NAME)).build())
        .build();

    /** A provider is looked up by its name or by the permission it demands. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(SiteAuthProviderModel.NAME, SiteAuthProviderModel.REQUIRED_PERMISSION);
    }

    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "auth_provider"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "auth_provider"); }
    @Override public @NonNull String slug() { return "auth-providers"; }
    @Override public @NonNull Model model() { return Models.get(SiteAuthProviderModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 50; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("key"); }


    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        normalizeConfig(values, null, null);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        @SuppressWarnings("unchecked")
        Map<String, Object> existingConfig = (Map<String, Object>) existing.get(SiteAuthProviderModel.CONFIG);
        Map<String, Object> values = CmsSupport.mutable(coerced);
        normalizeConfig(values, existing, existingConfig);
        super.updateRow(existing, values, accessContext);
    }

    /**
     * Store the config in the shape its provider type declares.
     *
     * AIDEV-NOTE: the inline cell lane hands updateRow a map holding EXACTLY ONE entry.
     * The type is therefore read through the stored value (reading it off the map refused
     * a rename with "unknown_provider_type"), and a write that carries no {@code config}
     * key leaves the column alone entirely -- normalizing an ABSENT config would have run
     * the provider's storage shape over an empty submission and written the result.
     *
     * @param existing the stored row, or null on a create
     */
    @SuppressWarnings("unchecked")
    private static void normalizeConfig(@NonNull Map<String, Object> coerced,
                                        @Nullable Row existing,
                                        @Nullable Map<String, Object> existingConfig) {
        Object typeValue = CmsSupport.valueOf(coerced, existing,
            SiteAuthProviderModel.PROVIDER_TYPE);
        String providerType = typeValue != null ? String.valueOf(typeValue) : null;
        SiteAuthProviderTypeHandler handler = SiteAuthProviders.getHandler(providerType);
        if (handler == null) {
            // AIDEV-NOTE: An unknown provider type MUST fail loudly. Storing the submitted config
            // as-is would bypass the provider's canonical storage normalization.
            throw Violations.ofField("provider_type", providerType,
                CmsSupport.violationText("unknown_provider_type").withArg("type", providerType));
        }
        if (existing != null && !coerced.containsKey(SiteAuthProviderModel.CONFIG.getName())) {
            return;
        }
        Object rawConfig = coerced.get(SiteAuthProviderModel.CONFIG.getName());
        Map<String, Object> submitted = rawConfig instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
        coerced.put("config", handler.normalizeConfigForSave(submitted, existingConfig));
    }
}
