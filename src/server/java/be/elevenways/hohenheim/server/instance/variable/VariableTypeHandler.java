package be.elevenways.hohenheim.server.instance.variable;

import be.elevenways.hohenheim.instance.VariableTypeInfo;
import be.elevenways.protoblast.common.annotation.BlastDiscoverable;
import be.elevenways.zenit.common.orm.field.Field;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Server half of a variable type: builds the REAL zenit field a submitted value is
 * coerced and validated through, so a bad value is a TYPED Violations refusal from the
 * standard submit pipeline -- never a rule-string regex miss. Implementations are
 * discovered at compile time (the InstanceKindHandler shape); adding a type ships one
 * class, registered nowhere manually.
 */
@BlastDiscoverable(registrar = "be.elevenways.hohenheim.server.instance.variable.VariableTypes#register")
public interface VariableTypeHandler extends VariableTypeInfo {

    /**
     * The typed form field for one declared variable. The field's name is the variable
     * KEY, so submitted form values land under it and violations anchor on it.
     *
     * @param settings the variable row's per-type settings (this type's own schema)
     */
    @NonNull Field<?, ?> buildField(@NonNull String key, @NonNull String label,
                                    boolean required, @NonNull Map<String, Object> settings);

    /**
     * The stored string form of a coerced value (variables persist as strings; the
     * typed field re-coerces on the way back out).
     */
    default @Nullable String toStoredString(@Nullable Object coerced) {
        return coerced == null ? null : String.valueOf(coerced);
    }
}
