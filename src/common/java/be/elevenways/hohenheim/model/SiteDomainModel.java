package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import java.util.List;

public class SiteDomainModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "site_domain");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id").build());
    public static final StringField HOSTNAME = SCHEMA.addField(StringField.builder().name("hostname").build());
    public static final StringField MATCH_TYPE = SCHEMA.addField(StringField.builder().name("match_type").build());
    public static final StringField LISTEN_ON = SCHEMA.addField(StringField.builder().name("listen_on").build());
    public static final IntegerField PORT = SCHEMA.addField(IntegerField.builder().name("port").build());
    public static final StringField PATH = SCHEMA.addField(StringField.builder().name("path").build());
    public static final BooleanField STRIP_PATH = SCHEMA.addField(BooleanField.builder("strip_path").defaultValue(false).build());
    public static final BooleanField FORCE_SSL = SCHEMA.addField(BooleanField.builder("force_ssl").defaultValue(true).build());
    public static final StringField CERTIFICATE_ID = SCHEMA.addField(StringField.builder().name("certificate_id").build());
    public static final StringField CERTIFICATE_TYPE = SCHEMA.addField(StringField.builder().name("certificate_type").build());
    public static final BooleanField HSTS_ENABLED = SCHEMA.addField(BooleanField.builder("hsts_enabled").defaultValue(false).build());
    public static final BooleanField HSTS_SUBDOMAINS = SCHEMA.addField(BooleanField.builder("hsts_subdomains").defaultValue(false).build());
    public static final BooleanField HTTP2_SUPPORT = SCHEMA.addField(BooleanField.builder("http2_support").defaultValue(true).build());
    public static final SchemaField CUSTOM_HEADERS = SCHEMA.addField(SchemaField.builder("custom_headers").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    private final Datasource datasource;

    public SiteDomainModel(Datasource datasource) {
        this.datasource = datasource;
    }

    public List<Row> findBySiteId(int siteId) {
        return find()
            .where(SITE_ID.eq(siteId))
            .all();
    }

    public List<Row> findByHostname(String hostname) {
        return find()
            .where(HOSTNAME.eq(hostname))
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "SiteDomain"; }

    @Override
    public String getTableName() { return "site_domains"; }

    @Override
    public Schema getSchema() { return SCHEMA; }

    @Override
    protected Datasource getDatasource() { return this.datasource; }
}
