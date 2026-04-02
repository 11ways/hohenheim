package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * Stores captured stdout from managed child processes.
 */
public class ProclogModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "proclog");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id").build());
    public static final IntegerField PID = SCHEMA.addField(IntegerField.builder().name("pid").build());
    public static final StringField LOG_HTML = SCHEMA.addField(StringField.builder().name("log_html").build());
    public static final IntegerField LINE_COUNT = SCHEMA.addField(IntegerField.builder().name("line_count").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    private final Datasource datasource;

    public ProclogModel(Datasource datasource) {
        this.datasource = datasource;
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }
    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override
    public String getModelName() { return "Proclog"; }
    @Override
    public String getTableName() { return "proclogs"; }
    @Override
    public Schema getSchema() { return SCHEMA; }
    @Override
    protected Datasource getDatasource() { return this.datasource; }
}
