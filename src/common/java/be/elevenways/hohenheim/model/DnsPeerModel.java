package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

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
