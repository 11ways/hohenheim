package be.elevenways.hohenheim.server.instance;

import be.elevenways.zenit.common.coerce.PrimitiveCoercion;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE reading of a numeric ceiling a kind's settings declare, riding zenit's {@link PrimitiveCoercion}.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class KindSettingValues {

    private KindSettingValues() {
    }

    /** @return the declared whole number, or null when it is absent, blank, unparseable or not positive */
    static @Nullable Integer positive(@Nullable Object raw) {
        PrimitiveCoercion.Result<Integer> coerced = PrimitiveCoercion.toInteger(raw,
            PrimitiveCoercion.NumberRule.TRUNCATE, PrimitiveCoercion.TextRule.TRIMMED_BLANK_IS_NULL);
        Integer value = coerced.ok() ? coerced.value() : null;
        return value != null && value > 0 ? value : null;
    }
}
