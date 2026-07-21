package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.SiteAuthContext;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.UrlField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;

import java.util.Map;

/**
 * Proteus realm auth provider: gates proxied upstreams behind a remote Proteus realm and (when a
 * required permission is set) the identity's claimed permissions.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class ProteusAuthProviderType implements SiteAuthProviderTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "proteus");

    public static final String ENDPOINT = "endpoint";
    public static final String REALM_CLIENT = "realm_client";
    public static final String ACCESS_KEY = "access_key";
    public static final String AUTHENTICATOR = "authenticator";

    public static final Schema CONFIG_SCHEMA = new Schema();
    static {
        CONFIG_SCHEMA.addField(UrlField.builder().name(ENDPOINT)
            .label(HohenheimFormCopy.label("proteus_endpoint"))
            .help(HohenheimFormCopy.help("proteus_endpoint")).build());
        CONFIG_SCHEMA.addField(StringField.builder().name(REALM_CLIENT)
            .label(HohenheimFormCopy.label("proteus_realm_client"))
            .help(HohenheimFormCopy.help("proteus_realm_client")).build());
        CONFIG_SCHEMA.addField(StringField.builder().name(ACCESS_KEY).secret()
            .label(HohenheimFormCopy.label("proteus_access_key"))
            .help(HohenheimFormCopy.help("proteus_access_key")).build());
        CONFIG_SCHEMA.addField(StringField.builder().name(AUTHENTICATOR)
            .label(HohenheimFormCopy.label("proteus_authenticator"))
            .help(HohenheimFormCopy.help("proteus_authenticator")).build());
    }

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() {
        return "Proteus SSO";
    }

    @Override
    public Schema getSchema() {
        return CONFIG_SCHEMA;
    }

    @Override
    public Icon getIcon() {
        return Icon.of("shield-halved");
    }

    @Override
    public String getColor() {
        return "indigo";
    }

    @Override
    public SiteAuthGate createGate(SiteAuthContext context) {
        Map<String, Object> config = configMap(context);
        String endpoint = str(config.get(ENDPOINT));
        String realmClient = str(config.get(REALM_CLIENT));
        String accessKey = str(config.get(ACCESS_KEY));
        // Blank = no forced slug: Proteus then serves its chooser page offering
        // every enabled authenticator. (The old blank->"password" default was a
        // bug: no such authenticator type exists, so blank configs failed closed.)
        String authenticator = str(config.get(AUTHENTICATOR));
        if (authenticator != null && authenticator.isBlank()) {
            authenticator = null;
        }

        // Pure construction (validates config, no network I/O); a blank-config throw fails closed.
        ProteusClient client = new ProteusClient(endpoint, realmClient, accessKey);
        long ttl = HohenheimSettings.VALUES.getValue(HohenheimSettings.ProxyAuth.PERSISTENT_TTL_SECONDS);
        return new ProteusAuthGate(context, client, authenticator,
            (int) Math.min(ttl, Integer.MAX_VALUE));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configMap(SiteAuthContext context) {
        Object raw = context.config().get(SiteAuthProviderModel.CONFIG);
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
