package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * The stored status of one install-media fetch running off the request thread: what the Install media
 * tab renders while the ISO downloads and imports, and why it failed when it did.
 *
 * AIDEV-NOTE: the fetched URL is deliberately NOT stored: an operator may paste one carrying
 * credentials, and nothing after the request needs it. {@code state} is an
 * {@code InstallMediaFetchState} token; the rows are ephemeral and cascade with their host.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public class InstallMediaFetchModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "install_media_fetch");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    /** The Incus host whose managed pool receives the medium. */
    public static final IntegerField SERVER_ID = SCHEMA.addField(IntegerField.builder().name("server_id").build());

    /** The medium's volume name on that host. */
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());

    public static final StringField STATE = SCHEMA.addField(StringField.builder().name("state").build());

    /** The downloaded fraction in [0, 1], null while the size is unknown. */
    public static final DoubleField PROGRESS = SCHEMA.addField(DoubleField.builder().name("progress").build());

    /** Why a failed fetch failed, operator-facing (the same text the synchronous lane flashed). */
    public static final TextField ERROR = SCHEMA.addField(
        TextField.builder().name("error").filterable(false).build());

    public static final DateTimeField FINISHED_AT = SCHEMA.addField(
        DateTimeField.builder().name("finished_at").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "InstallMediaFetch"; }
    @Override public String getTableName() { return "install_media_fetches"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
