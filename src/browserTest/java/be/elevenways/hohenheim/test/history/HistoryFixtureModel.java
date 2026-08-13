package be.elevenways.hohenheim.test.history;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.behaviour.RevisionableBehaviour;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A revisionable model shaped exactly like the production one this purge is about: a plain
 * column, a column that becomes secret later, and a DYNAMIC (schemaFrom) JSON carrier holding
 * one plain and one later-secret sub-key -- SiteModel's {@code settings} in miniature, which
 * is where every credential the audit enumerated actually lives.
 *
 * AIDEV-NOTE: the constructor takes a Datasource ON PURPOSE. Auto-discovery emits
 * {@code Models.registerInstance(new X())} for concrete models with a PUBLIC NO-ARG
 * constructor, and a fixture model registered in every browser-test JVM would make
 * SchemaDriftChecker report a missing table on every boot that is not this test. The
 * datasource-constructor shape is the framework's documented opt-out (RevisionModel's own),
 * so this model exists only while the test explicitly registers it.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public class HistoryFixtureModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheimtest", "history_fixture");
    public static final String TABLE = "history_purge_fixture";

    /** The sub-schema the {@code alpha} kind selects, mirroring a site type's settings. */
    public static final Schema ALPHA = new Schema();
    public static final StringField ALPHA_KEEP =
        ALPHA.addField(StringField.builder("keep").build());
    public static final ToggleSecretField ALPHA_TOKEN =
        ALPHA.addField(ToggleSecretField.named("nested_token"));

    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField LABEL = SCHEMA.addField(StringField.builder("label").build());
    public static final ToggleSecretField TOKEN = SCHEMA.addField(ToggleSecretField.named("token"));

    /** Declared BEFORE the carrier: schemaFrom resolves its sibling out of this schema. */
    public static final EnumField KIND = SCHEMA.addField(EnumField.builder("kind")
        .value("alpha", "Alpha", ALPHA)
        .build());

    public static final SchemaField CONFIG = SCHEMA.addField(
        SchemaField.builder("config").schemaFrom("kind").build());

    public static final RevisionableBehaviour REVISIONABLE =
        SCHEMA.addBehaviour(RevisionableBehaviour.create(50));

    private final @Nullable Datasource datasource;

    public HistoryFixtureModel(@Nullable Datasource datasource) {
        this.datasource = datasource;
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "HistoryFixture"; }
    @Override public String getTableName() { return TABLE; }
    @Override public Schema getSchema() { return SCHEMA; }

    @Override
    protected @Nullable Datasource getDatasource() {
        return this.datasource != null ? this.datasource : super.getDatasource();
    }
}
