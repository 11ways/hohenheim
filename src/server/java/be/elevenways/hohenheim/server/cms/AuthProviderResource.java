package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderTypeHandler;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Per-site proxy auth providers with a type-discriminated config sub-form.
 * The save path routes the submitted config through the provider type's
 * normalizeConfigForSave, which hashes secrets and carries unchanged ones
 * forward -- an unknown type fails loudly so plaintext never persists.
 */
public final class AuthProviderResource extends HohenheimRowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(SiteAuthProviderModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteAuthProviderModel.PROVIDER_TYPE))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteAuthProviderModel.CONFIG))
        .add(SiteAuthProviderModel.REQUIRED_PERMISSION)
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "auth_provider"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.auth_provider.plural"); }
    @Override public @NonNull String slug() { return "auth-providers"; }
    @Override public @NonNull Model model() { return Models.get(SiteAuthProviderModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 50; }
    @Override public @NonNull Icon icon() { return Icon.of("key"); }

    @Override protected @NonNull String auditResourceType() { return AuditLogModel.RESOURCE_AUTH_PROVIDER; }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = mutable(coerced);
        normalizeConfig(values, null);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        @SuppressWarnings("unchecked")
        Map<String, Object> existingConfig = (Map<String, Object>) existing.get(SiteAuthProviderModel.CONFIG);
        Map<String, Object> values = mutable(coerced);
        normalizeConfig(values, existingConfig);
        super.updateRow(existing, values, accessContext);
    }

    @SuppressWarnings("unchecked")
    private static void normalizeConfig(@NonNull Map<String, Object> coerced,
                                        @Nullable Map<String, Object> existingConfig) {
        Object typeValue = coerced.get("provider_type");
        String providerType = typeValue != null ? String.valueOf(typeValue) : null;
        SiteAuthProviderTypeHandler handler = SiteAuthProviders.getHandler(providerType);
        if (handler == null) {
            // AIDEV-NOTE: An unknown provider type MUST fail loudly. Storing the submitted config
            // as-is would persist Basic passwords in plaintext (normalizeConfigForSave hashes them).
            throw new IllegalStateException("Unknown auth provider type: " + providerType);
        }
        Object rawConfig = coerced.get("config");
        Map<String, Object> submitted = rawConfig instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
        coerced.put("config", handler.normalizeConfigForSave(submitted, existingConfig));
    }
}
