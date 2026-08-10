package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * The dyndns2 update credential for ONE address record: existence = the record is
 * dynamic, {@code token_digest} is the sha256 of the plaintext the client presents.
 *
 * AIDEV-NOTE: its own table on purpose (typed shapes get real tables): dynamic DNS is
 * a capability of A/AAAA records only, so its state must not be columns every TXT and
 * NS row carries. Only the DIGEST is at rest -- the mint row action discloses the
 * plaintext once and {@code DynamicDnsService.authenticate} looks the row up by digest
 * (indexed; routers poll constantly). There is no enabled flag: revoking deletes the
 * row, which is what makes a released hostname's token die with it.
 */
public class DnsDyndnsCredentialModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "dns_dyndns_credential");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField RECORD_ID = SCHEMA.addField(
        IntegerField.builder().name("record_id").build());
    public static final StringField TOKEN_DIGEST = SCHEMA.addField(
        StringField.builder().name("token_digest").secret().build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "DnsDyndnsCredential"; }

    @Override
    public String getTableName() { return "dns_dyndns_credentials"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
