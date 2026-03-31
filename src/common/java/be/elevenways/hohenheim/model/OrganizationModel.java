package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

public class OrganizationModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "organization");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final StringField SLUG = SCHEMA.addField(StringField.builder().name("slug").build());
    public static final StringField OWNER_ID = SCHEMA.addField(StringField.builder().name("owner_id").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    private final Datasource datasource;

    public OrganizationModel(Datasource datasource) {
        this.datasource = datasource;
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Organization"; }

    @Override
    public String getTableName() { return "organizations"; }

    @Override
    public Schema getSchema() { return SCHEMA; }

    @Override
    protected Datasource getDatasource() { return this.datasource; }
}
