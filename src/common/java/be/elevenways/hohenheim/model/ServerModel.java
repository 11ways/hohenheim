package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * A Docker host the platform can manage: the implicit {@code local} daemon, or a remote one
 * reached over SSH ({@code mode = "ssh"}, {@code ssh_target} like {@code user@host}). The basis of
 * the multi-server inventory; {@code DockerClient}s are built per server from these records.
 */
public class ServerModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "server");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final StringField MODE = SCHEMA.addField(StringField.builder().name("mode").build());
    public static final StringField SSH_TARGET = SCHEMA.addField(StringField.builder().name("ssh_target").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());


    /** The server with this unique name, or null if none. */
    public Row findByName(String name) {
        return find().where(NAME.eq(name)).first();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Server"; }

    @Override
    public String getTableName() { return "servers"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
