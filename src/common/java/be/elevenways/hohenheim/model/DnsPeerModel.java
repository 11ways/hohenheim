package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Another Hohenheim (or plain nameserver) this instance federates with: a DNS
 * zone-transfer channel (TSIG-authenticated AXFR + NOTIFY) plus, for a full
 * Hohenheim peer, an HTTPS admin base URL and API key used to forward edits of
 * zones that peer owns.
 */
public class DnsPeerModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "dns_peer");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("peer_name")).help(HohenheimFormCopy.help("peer_name")).build());

    /** {@link #PEER_TYPE} value for a peer running Hohenheim: transfers AND forwarded edits. */
    public static final String TYPE_HOHENHEIM = "hohenheim";
    /** {@link #PEER_TYPE} value for any other nameserver: zone transfers only. */
    public static final String TYPE_NAMESERVER = "nameserver";

    /**
     * What kind of software the peer runs, which decides which of the two channels it has.
     *
     * AIDEV-NOTE: this replaced an implicit "has a base URL and an API key" null-check in
     * {@code DnsPeerApi.forPeer}. A half-filled Hohenheim peer used to degrade silently
     * into a plain nameserver; now the type is DECLARED, the form asks for exactly the
     * channel that type has, and a missing credential is a refusal instead of a shrug.
     */
    public static final EnumField PEER_TYPE = SCHEMA.addField(EnumField.builder("peer_type")
        .defaultValue(TYPE_NAMESERVER)
        .value(TYPE_NAMESERVER, v -> v.displayName("Nameserver")
            .label(Microcopy.of("type_nameserver").withFilter("scope", "dns_peer"))
            .icon("server").color("gray"))
        .value(TYPE_HOHENHEIM, v -> v.displayName("Hohenheim")
            .label(Microcopy.of("type_hohenheim").withFilter("scope", "dns_peer"))
            .icon("handshake").color("blue"))
        .label(HohenheimFormCopy.label("peer_type")).help(HohenheimFormCopy.help("peer_type")).build());
    public static final StringField BASE_URL = SCHEMA.addField(StringField.builder().name("base_url")
        .label(HohenheimFormCopy.label("peer_base_url")).help(HohenheimFormCopy.help("peer_base_url")).build());
    public static final StringField API_KEY = SCHEMA.addField(StringField.builder().name("api_key").secret().encrypted()
        .label(HohenheimFormCopy.label("peer_api_key")).help(HohenheimFormCopy.help("peer_api_key")).build());
    public static final StringField TRANSFER_HOST = SCHEMA.addField(StringField.builder().name("transfer_host")
        .label(HohenheimFormCopy.label("peer_transfer_host")).help(HohenheimFormCopy.help("peer_transfer_host")).build());
    public static final IntegerField TRANSFER_PORT = SCHEMA.addField(IntegerField.builder().name("transfer_port")
        .defaultValue(53).label(HohenheimFormCopy.label("peer_transfer_port"))
        .help(HohenheimFormCopy.help("peer_transfer_port")).build());
    public static final StringField TSIG_KEY_NAME = SCHEMA.addField(StringField.builder().name("tsig_key_name")
        .label(HohenheimFormCopy.label("peer_tsig_key_name")).help(HohenheimFormCopy.help("peer_tsig_key_name")).build());
    public static final StringField TSIG_ALGORITHM = SCHEMA.addField(StringField.builder().name("tsig_algorithm")
        .label(HohenheimFormCopy.label("peer_tsig_algorithm")).help(HohenheimFormCopy.help("peer_tsig_algorithm")).build());
    public static final StringField TSIG_SECRET = SCHEMA.addField(StringField.builder().name("tsig_secret").secret().encrypted()
        .label(HohenheimFormCopy.label("peer_tsig_secret")).help(HohenheimFormCopy.help("peer_tsig_secret")).build());
    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled").defaultValue(true)
        .label(HohenheimFormCopy.label("peer_enabled")).help(HohenheimFormCopy.help("peer_enabled")).build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public List<Row> findEnabled() {
        return find().where(ENABLED.eq(true)).all();
    }

    public @Nullable Row findByName(@NonNull String name) {
        return find().where(NAME.eq(name)).first();
    }

    /** @return the peer authenticating with this TSIG key name, comparing them canonically */
    public @Nullable Row findByTsigKeyName(@NonNull String keyName) {
        String canonical = canonicalKeyName(keyName);
        for (Row peer : find().all()) {
            String stored = peer.get(TSIG_KEY_NAME);
            if (stored != null && canonicalKeyName(stored).equals(canonical)) {
                return peer;
            }
        }
        return null;
    }

    /** TSIG key names are DNS names: compared lowercased and without the root dot. */
    private static @NonNull String canonicalKeyName(@NonNull String keyName) {
        String canonical = BlastString.lower(keyName.trim());
        while (canonical.endsWith(".")) {
            canonical = canonical.substring(0, canonical.length() - 1);
        }
        return canonical;
    }

    /** @return the peer's declared type, defaulting a null/unknown column to a plain nameserver */
    public static @NonNull String typeOf(@NonNull Row peer) {
        return TYPE_HOHENHEIM.equals(peer.get(PEER_TYPE)) ? TYPE_HOHENHEIM : TYPE_NAMESERVER;
    }

    /** @return true when the peer runs Hohenheim, so it also has the edit-forwarding channel */
    public static boolean isHohenheim(@NonNull Row peer) {
        return TYPE_HOHENHEIM.equals(typeOf(peer));
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "DnsPeer"; }

    @Override
    public String getTableName() { return "dns_peers"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
