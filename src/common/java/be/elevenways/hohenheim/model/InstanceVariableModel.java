package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;

import java.util.List;

/**
 * One variable VALUE of one instance. Table-backed rows, never a free settings map,
 * because zenit refuses {@code .encrypted()} inside JSON sub-schemas by design: a
 * secret value lives ONLY in the statically declared encrypted {@code secret_value}
 * column, a plain value ONLY in {@code plain_value}, and the write funnel enforces
 * exactly one carrier per {@code kind} -- a runtime flag never pretends to change a
 * field declaration.
 */
public class InstanceVariableModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_variable");
    public static final Schema SCHEMA = new Schema();

    /** {@link #KIND}: the value is visible data ({@code plain_value}). */
    public static final String KIND_PLAIN = "plain";

    /** {@link #KIND}: the value is a secret ({@code secret_value}, encrypted at rest). */
    public static final String KIND_SECRET = "secret";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id").build());

    public static final StringField KEY = SCHEMA.addField(StringField.builder().name("key")
        .required()
        .label(HohenheimFormCopy.label("variable_key"))
        .build());

    public static final EnumField KIND = SCHEMA.addField(EnumField.builder("kind")
        .value(KIND_PLAIN, v -> v.displayName("Plain")
            .label(Microcopy.of("plain").withFilter("scope", "variable_kind")).color("gray"))
        .value(KIND_SECRET, v -> v.displayName("Secret").icon("key")
            .label(Microcopy.of("secret").withFilter("scope", "variable_kind")).color("orange"))
        .defaultValue(KIND_PLAIN)
        .build());

    public static final TextField PLAIN_VALUE = SCHEMA.addField(TextField.builder().name("plain_value")
        .label(HohenheimFormCopy.label("variable_value"))
        .build());

    // secret() masks it on every form surface and keeps it out of derived surfaces;
    // encrypted() is the at-rest representation. Both are STATIC declarations.
    public static final TextField SECRET_VALUE = SCHEMA.addField(TextField.builder().name("secret_value")
        .secret()
        .encrypted()
        .label(HohenheimFormCopy.label("variable_value"))
        .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(KEY);
        // ONE value carrier per kind, on EVERY writer (form, service, direct save):
        // a secret row must never carry a plaintext copy in plain_value, and a plain
        // row must never smuggle data into the encrypted column where redaction
        // rules would hide it from the operator.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            boolean secret = KIND_SECRET.equals(effective(row, KIND.getName()));
            String wrongCarrier = secret ? PLAIN_VALUE.getName() : SECRET_VALUE.getName();
            Object stray = row.has(wrongCarrier) ? row.get(wrongCarrier) : null;
            if (stray != null && !String.valueOf(stray).isEmpty()) {
                throw Violations.ofField(wrongCarrier, null,
                    Microcopy.of("variable_wrong_carrier").withFilter("scope", "violations")
                        .withArg("kind", secret ? KIND_SECRET : KIND_PLAIN));
            }
        });
    }

    /** The staged value when carried, else the stored one (partial updates carry only changes). */
    private static Object effective(Row row, String name) {
        if (row.has(name)) {
            return row.get(name);
        }
        Object id = row.has(ID.getName()) ? row.get(ID.getName()) : null;
        if (id == null) {
            return null;
        }
        Row stored = be.elevenways.zenit.common.orm.model.Models.get(InstanceVariableModel.class)
            .findById(id);
        return stored != null ? stored.get(name) : null;
    }

    /** All variables of one instance, stable key order. */
    public List<Row> findByInstanceId(int instanceId) {
        return find().where(INSTANCE_ID.eq(instanceId)).orderBy(KEY, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "InstanceVariable"; }

    @Override
    public String getTableName() { return "instance_variables"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
