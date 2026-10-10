package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.auth.SiteAuthContext;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderTypeHandler;
import be.elevenways.hohenheim.server.proxy.auth.ProxySessionSupport;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.UrlField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.Objects;

/**
 * Proteus realm auth provider: gates proxied upstreams behind a remote Proteus realm and (when a
 * required permission is set) the identity's claimed permissions.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class ProteusAuthProviderType implements SiteAuthProviderTypeHandler {

    public static final Identifier ID = HohenheimIds.id("proteus");

    public static final String ENDPOINT = "endpoint";
    public static final String REALM_CLIENT = "realm_client";
    public static final String ACCESS_KEY = "access_key";
    public static final String AUTHENTICATOR = "authenticator";

    public static final Schema CONFIG_SCHEMA = new Schema();
    static {
        CONFIG_SCHEMA.addField(UrlField.builder().name(ENDPOINT)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("proteus_endpoint"))
            .help(HohenheimMicrocopy.HELP.of("proteus_endpoint")).build());
        CONFIG_SCHEMA.addField(StringField.builder().name(REALM_CLIENT)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("proteus_realm_client"))
            .help(HohenheimMicrocopy.HELP.of("proteus_realm_client")).build());
        CONFIG_SCHEMA.addField(StringField.builder().name(ACCESS_KEY).secret()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("proteus_access_key"))
            .help(HohenheimMicrocopy.HELP.of("proteus_access_key")).build());
        CONFIG_SCHEMA.addField(StringField.builder().name(AUTHENTICATOR)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("proteus_authenticator"))
            .help(HohenheimMicrocopy.HELP.of("proteus_authenticator")).build());
    }

    /**
     * The guard a provider's realm calls ride. AIDEV-NOTE: a provider's endpoint is config whoever manages the provider
     * may edit, never the operator's own declaration (that is {@code hohenheim.auth_proteus}, which rides
     * {@link OutboundUrlGuard#ANY_ADDRESS}), so it gets the public internet, or the private networks on the operator's
     * explicit opt-in; this host and link-local stay refused either way.
     */
    public static @NonNull OutboundUrlGuard realmGuard() {
        return OutboundUrlGuard.optingIn(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS);
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
    public BadgeColor color() {
        return ColorHue.INDIGO;
    }

    @Override
    public SiteAuthGate createGate(SiteAuthContext context) {
        Map<String, Object> config = context.providerSettings();
        String endpoint = Objects.toString(config.get(ENDPOINT), null);
        String realmClient = Objects.toString(config.get(REALM_CLIENT), null);
        String accessKey = Objects.toString(config.get(ACCESS_KEY), null);
        // Blank = no forced slug: Proteus then serves its chooser page offering
        // every enabled authenticator. (The old blank->"password" default was a
        // bug: no such authenticator type exists, so blank configs failed closed.)
        String authenticator = Objects.toString(config.get(AUTHENTICATOR), null);
        if (authenticator != null && authenticator.isBlank()) {
            authenticator = null;
        }

        // Pure construction (validates config, no network I/O); a blank-config throw fails closed.
        ProteusClient client = new ProteusClient(endpoint, realmClient, accessKey, realmGuard());
        long ttl = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.ProxyAuth.PERSISTENT_TTL_SECONDS);
        // AIDEV-NOTE: the binding is the realm IDENTITY (endpoint + realm client): re-pointing the
        // provider at another realm ends every session it minted, while rotating the access key
        // or changing the forced authenticator does not log anyone out.
        String binding = ProxySessionSupport.binding(context.providerSlug(),
            String.valueOf(context.providerId()), endpoint, realmClient);
        return new ProteusAuthGate(context, client, authenticator,
            (int) Math.min(ttl, Integer.MAX_VALUE), binding);
    }
}
