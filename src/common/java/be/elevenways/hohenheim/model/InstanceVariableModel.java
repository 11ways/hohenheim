package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.instance.VariableKind;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.validation.ValidationMicrocopy;
import be.elevenways.zenit.common.validation.validator.Required;

import java.util.List;

/**
 * One variable VALUE of one instance OR one environment (exactly one owner, enforced
 * below -- environments group variables through the SAME mechanism, never a second
 * table). Table-backed rows, never a free settings map, because zenit refuses
 * {@code .encrypted()} inside JSON sub-schemas by design: a secret value lives ONLY
 * in the statically declared encrypted {@code secret_value} column, a plain value
 * ONLY in {@code plain_value}, and the write funnel enforces exactly one carrier per
 * {@code kind} -- a runtime flag never pretends to change a field declaration.
 * Environment values are the deploy-time BASELINE; the instance's own row for the
 * same key wins (InstanceVariables.valuesFor).
 */
public class InstanceVariableModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("instance_variable");
    public static final Schema SCHEMA = new Schema();

    /** {@link #KIND}: the value is visible data ({@code plain_value}). */
    public static final String KIND_PLAIN = "plain";

    /** {@link #KIND}: the value is a secret ({@code secret_value}, encrypted at rest). */
    public static final String KIND_SECRET = "secret";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id").build());

    /** The owning environment (environments.id) when this is an environment-scoped value. */
    public static final IntegerField ENVIRONMENT_ID = SCHEMA.addField(
        IntegerField.builder().name("environment_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("environment"))
            .build());

    public static final StringField KEY = SCHEMA.addField(StringField.builder().name("key")
        .required()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("variable_key"))
        .build());

    public static final EnumField KIND = SCHEMA.addField(EnumField.builder("kind")
        .value(KIND_PLAIN, v -> v.displayName("Plain")
            .label(HohenheimMicrocopy.VARIABLE_KIND.of("plain")).color(ColorHue.GRAY))
        .value(KIND_SECRET, v -> v.displayName("Secret").icon("key")
            .label(HohenheimMicrocopy.VARIABLE_KIND.of("secret")).color(ColorHue.ORANGE))
        .defaultValue(KIND_PLAIN)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("variable_kind"))
        .build());

    public static final TextField PLAIN_VALUE = SCHEMA.addField(TextField.builder().name("plain_value")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("variable_value"))
        .build());

    // secret() masks it on every form surface and keeps it out of derived surfaces;
    // encrypted() is the at-rest representation. Both are STATIC declarations.
    // AIDEV-NOTE: its own label, deliberately NOT "variable_value". The two carriers are
    // separate columns on one form, and two entries labelled "Value" leave the operator
    // guessing which one is stored -- the label is the only thing telling them apart.
    public static final TextField SECRET_VALUE = SCHEMA.addField(TextField.builder().name("secret_value")
        .secret()
        .encrypted()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("variable_secret_value"))
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
            Row stored = StoredRows.of(Models.get(InstanceVariableModel.class), row);
            Object kindValue = row.afterWrite(KIND, stored);
            VariableKind kind = VariableKind.parse(kindValue);
            if (kind == null) {
                throw Violations.ofField(KIND.getName(), kindValue,
                    HohenheimMicrocopy.VIOLATIONS.of("variable_kind_unknown").withArg("kind", kindValue));
            }
            boolean secret = kind.isSecret();
            String wrongCarrier = secret ? PLAIN_VALUE.getName() : SECRET_VALUE.getName();
            Object stray = row.has(wrongCarrier) ? row.get(wrongCarrier) : null;
            if (stray != null && !String.valueOf(stray).isEmpty()) {
                throw Violations.ofField(wrongCarrier, null,
                    HohenheimMicrocopy.VIOLATIONS.of("variable_wrong_carrier")
                        .withArg("kind", secret ? KIND_SECRET : KIND_PLAIN));
            }
            Object secretValue = secret ? row.afterWrite(SECRET_VALUE, stored) : null;
            if (secret && (secretValue == null || secretValue.toString().isEmpty())) {
                throw Violations.ofField(SECRET_VALUE.getName(), null,
                    ValidationMicrocopy.of(Required.DEFAULT_MESSAGE_KEY).withArg("field", SECRET_VALUE.getLabel()));
            }
        });
        // Exactly ONE owner per row, on every writer: a value belongs to an instance
        // or to an environment, never both (which key wins?) and never neither
        // (an orphan value no consumer ever reads).
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            boolean carriesEither = row.has(INSTANCE_ID.getName())
                || row.has(ENVIRONMENT_ID.getName());
            if (!carriesEither) {
                // A partial update that touches neither owner column leaves the
                // stored (already-validated) owner untouched.
                return;
            }
            Row stored = StoredRows.of(Models.get(InstanceVariableModel.class), row);
            Object instance = row.afterWrite(INSTANCE_ID, stored);
            Object environment = row.afterWrite(ENVIRONMENT_ID, stored);
            if ((instance == null) == (environment == null)) {
                throw Violations.ofField(ENVIRONMENT_ID.getName(), environment,
                    HohenheimMicrocopy.VIOLATIONS.of("variable_one_owner"));
            }
        });
    }

    /** All variables of one instance, stable key order. */
    public List<Row> findByInstanceId(int instanceId) {
        return find().where(INSTANCE_ID.eq(instanceId)).orderBy(KEY, SortOrder.ASC).all();
    }

    /** All variables of one environment, stable key order. */
    public List<Row> findByEnvironmentId(int environmentId) {
        return find().where(ENVIRONMENT_ID.eq(environmentId)).orderBy(KEY, SortOrder.ASC).all();
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
