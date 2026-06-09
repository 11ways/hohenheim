package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.auth.SiteAuthProviderType;
import be.elevenways.hohenheim.auth.SiteAuthProviderTypeRegistry;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderTypeHandler;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.auth.types.ProteusAuthProviderType;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.RenderTemplateResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD for per-site auth-provider records. The create/edit form renders the polymorphic provider
 * schema from {@link SiteAuthProviderTypeRegistry}; the save path delegates secret handling to the
 * provider type's normalizeConfigForSave.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class AuthProviderHandlers {

    private static final String DEFAULT_TYPE = ProteusAuthProviderType.ID.toString();

    private AuthProviderHandlers() {
    }

    public static void init() {
        SiteAuthProviderModel model = Models.get(SiteAuthProviderModel.class);

        HohenheimEndpoints.AUTH_PROVIDERS.setHandler(conduit -> {
            List<Map<String, Object>> providers = new ArrayList<>();
            for (Row row : model.findAllOrdered()) {
                Integer providerId = row.get(SiteAuthProviderModel.ID);
                Map<String, Object> item = new HashMap<>();
                item.put("id", providerId);
                item.put("name", row.get(SiteAuthProviderModel.NAME));
                item.put("type", typeLabel(row.get(SiteAuthProviderModel.PROVIDER_TYPE)));
                item.put("requiredPermission",
                    HandlerSupport.valueOrEmpty(row.get(SiteAuthProviderModel.REQUIRED_PERMISSION)));
                item.put("editUrl", HohenheimEndpoints.AUTH_PROVIDERS_EDIT
                    .with(HohenheimEndpoints.AUTH_PROVIDER_ID, providerId).toUrl());
                item.put("deleteUrl", HohenheimEndpoints.AUTH_PROVIDERS_DELETE
                    .with(HohenheimEndpoints.AUTH_PROVIDER_ID, providerId).toUrl());
                providers.add(item);
            }
            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/auth-providers/list"),
                Map.of("authProviders", providers));
        });

        HohenheimEndpoints.AUTH_PROVIDERS_CREATE_FORM.setHandler(conduit ->
            new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/auth-providers/create"),
                createVars("")));

        HohenheimEndpoints.AUTH_PROVIDERS_CREATE.setHandler(conduit -> {
            Map<String, String> form = HandlerSupport.formMap(conduit);
            String name = form.getOrDefault("name", "").trim();
            String providerType = form.getOrDefault("provider_type", DEFAULT_TYPE).trim();

            if (name.isEmpty()) {
                Map<String, Object> vars = createVars("Name is required");
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/auth-providers/create"), vars);
            }

            Row row = model.createEmptyRow();
            row.set(SiteAuthProviderModel.NAME, name);
            row.set(SiteAuthProviderModel.PROVIDER_TYPE, providerType);
            applyConfigAndPermission(row, providerType, form, null);
            model.save(row);

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_CREATED,
                AuditLogModel.RESOURCE_AUTH_PROVIDER, row.get(SiteAuthProviderModel.ID), name);
            return HandlerSupport.redirectUntyped("/auth-providers");
        });

        HohenheimEndpoints.AUTH_PROVIDERS_EDIT.setHandler(conduit -> {
            Integer id = conduit.getParameter(HohenheimEndpoints.AUTH_PROVIDER_ID);
            Row row = model.findById(id);
            if (row == null) {
                return HandlerSupport.redirectUntyped("/auth-providers");
            }
            return HandlerSupport.renderUntyped(
                Identifier.of("hohenheim", "hohenheim/auth-providers/edit"), editVars(row, ""));
        });

        HohenheimEndpoints.AUTH_PROVIDERS_UPDATE.setHandler(conduit -> {
            Integer id = conduit.getParameter(HohenheimEndpoints.AUTH_PROVIDER_ID);
            Row row = model.findById(id);
            if (row == null) {
                return HandlerSupport.redirectUntyped("/auth-providers");
            }

            Map<String, String> form = HandlerSupport.formMap(conduit);
            String name = form.getOrDefault("name", "").trim();
            String providerType = form.getOrDefault("provider_type", DEFAULT_TYPE).trim();

            if (name.isEmpty()) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/auth-providers/edit"),
                    editVars(row, "Name is required"));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> existingConfig = (Map<String, Object>) row.get(SiteAuthProviderModel.CONFIG);

            row.set(SiteAuthProviderModel.NAME, name);
            row.set(SiteAuthProviderModel.PROVIDER_TYPE, providerType);
            applyConfigAndPermission(row, providerType, form, existingConfig);
            model.save(row);

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_UPDATED,
                AuditLogModel.RESOURCE_AUTH_PROVIDER, id, name);
            HandlerSupport.reloadProxy();
            return HandlerSupport.redirectUntyped("/auth-providers");
        });

        HohenheimEndpoints.AUTH_PROVIDERS_DELETE.setHandler(conduit -> {
            Integer id = conduit.getParameter(HohenheimEndpoints.AUTH_PROVIDER_ID);
            Row row = model.findById(id);
            if (row != null) {
                String name = row.get(SiteAuthProviderModel.NAME);
                model.find().where(SiteAuthProviderModel.ID.eq(id)).delete();
                HandlerSupport.audit(conduit, AuditLogModel.ACTION_DELETED,
                    AuditLogModel.RESOURCE_AUTH_PROVIDER, id, name);
                // Sites still referencing this provider fail closed until reconfigured.
                HandlerSupport.reloadProxy();
            }
            return HandlerSupport.redirectUntyped("/auth-providers");
        });
    }

    // Build the submitted config from the form for the given provider type, hand it to the type's
    // normalizeConfigForSave (which hashes secrets / carries forward unchanged ones), and store the
    // required permission (only meaningful for claims providers; harmless for the rest).
    // AIDEV-NOTE: An unknown provider type MUST fail loudly. Storing the submitted config as-is
    // would persist Basic passwords in plaintext (normalizeConfigForSave is what hashes them).
    private static void applyConfigAndPermission(Row row, String providerType,
                                                 Map<String, String> form,
                                                 Map<String, Object> existingConfig) {
        SiteAuthProviderTypeHandler handler = SiteAuthProviders.getHandler(providerType);
        if (handler == null) {
            throw new IllegalStateException("Unknown auth provider type: " + providerType);
        }
        Map<String, Object> submitted = buildSubmittedConfig(providerType, form);
        row.set(SiteAuthProviderModel.CONFIG, handler.normalizeConfigForSave(submitted, existingConfig));

        String requiredPermission = form.getOrDefault("required_permission", "").trim();
        row.set(SiteAuthProviderModel.REQUIRED_PERMISSION,
            requiredPermission.isEmpty() ? null : requiredPermission);
    }

    private static Map<String, Object> buildSubmittedConfig(String providerType, Map<String, String> form) {
        Map<String, Object> config = new HashMap<>();
        if (ProteusAuthProviderType.ID.toString().equals(providerType)) {
            config.put(ProteusAuthProviderType.ENDPOINT, form.getOrDefault("endpoint", "").trim());
            config.put(ProteusAuthProviderType.REALM_CLIENT, form.getOrDefault("realm_client", "").trim());
            config.put(ProteusAuthProviderType.ACCESS_KEY, form.getOrDefault("access_key", "").trim());
            config.put(ProteusAuthProviderType.AUTHENTICATOR, form.getOrDefault("authenticator", "").trim());
        } else if (BasicAuthProviderType.ID.toString().equals(providerType)) {
            List<Map<String, Object>> credentials = new ArrayList<>();
            for (Map<String, String> pair : HandlerSupport.extractIndexedPairs(form, "credentials")) {
                Map<String, Object> cred = new HashMap<>();
                cred.put(BasicAuthProviderType.USERNAME, pair.getOrDefault("name", ""));
                cred.put(BasicAuthProviderType.PASSWORD, pair.getOrDefault("value", ""));
                credentials.add(cred);
            }
            config.put(BasicAuthProviderType.CREDENTIALS, credentials);
        }
        return config;
    }

    private static Map<String, Object> createVars(String error) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("error", error);
        vars.put("authProviderTypes", typeOptions());
        vars.put("selectedType", DEFAULT_TYPE);
        return vars;
    }

    private static Map<String, Object> editVars(Row row, String error) {
        Map<String, Object> vars = new HashMap<>();
        String providerType = row.get(SiteAuthProviderModel.PROVIDER_TYPE);
        vars.put("error", error);
        vars.put("authProviderTypes", typeOptions());
        vars.put("id", row.get(SiteAuthProviderModel.ID));
        vars.put("name", HandlerSupport.valueOrEmpty(row.get(SiteAuthProviderModel.NAME)));
        vars.put("selectedType", providerType != null ? providerType : DEFAULT_TYPE);
        vars.put("requiredPermission",
            HandlerSupport.valueOrEmpty(row.get(SiteAuthProviderModel.REQUIRED_PERMISSION)));

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) row.get(SiteAuthProviderModel.CONFIG);
        config = config != null ? config : Map.of();
        vars.put("endpoint", HandlerSupport.valueOrEmpty(config.get(ProteusAuthProviderType.ENDPOINT)));
        vars.put("realmClient", HandlerSupport.valueOrEmpty(config.get(ProteusAuthProviderType.REALM_CLIENT)));
        vars.put("accessKey", HandlerSupport.valueOrEmpty(config.get(ProteusAuthProviderType.ACCESS_KEY)));
        vars.put("authenticator", HandlerSupport.valueOrEmpty(config.get(ProteusAuthProviderType.AUTHENTICATOR)));

        // Existing credentials as {name: username, value: ""} -- passwords are write-only, so the
        // editor shows usernames with blank password fields (blank carries the existing hash forward).
        List<Map<String, Object>> credentials = new ArrayList<>();
        for (Map<String, Object> cred : BasicAuthProviderType.credentialList(config)) {
            Map<String, Object> editorPair = new HashMap<>();
            editorPair.put("name", cred.getOrDefault(BasicAuthProviderType.USERNAME, ""));
            editorPair.put("value", "");
            credentials.add(editorPair);
        }
        vars.put("credentials", credentials);
        return vars;
    }

    private static List<Map<String, Object>> typeOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (SiteAuthProviderType type : SiteAuthProviderTypeRegistry.REGISTRY) {
            Identifier id = SiteAuthProviderTypeRegistry.REGISTRY.getId(type);
            if (id == null) {
                continue;
            }
            Map<String, Object> option = new HashMap<>();
            option.put("value", id.toString());
            option.put("label", type.getDisplayName());
            options.add(option);
        }
        return options;
    }

    private static String typeLabel(String providerType) {
        SiteAuthProviderTypeHandler handler = SiteAuthProviders.getHandler(providerType);
        return handler != null ? handler.getDisplayName() : HandlerSupport.valueOrEmpty(providerType);
    }
}
