package be.elevenways.hohenheim;

import be.elevenways.zenit.common.coerce.PrimitiveCoercion;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.text.Texts;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads of one untyped value (a request parameter, a settings or JSON map entry) as text, a number, a flag, a map or a
 * list.
 *
 * AIDEV-NOTE: both ride zenit's homes ({@link Texts}, {@link PrimitiveCoercion}); zenit has no "trimmed text or
 * empty" of its own, so a later framework helper replaces {@link #trimmed} here and nowhere else.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class RawValues {

    private RawValues() {
    }

    /** @return the value's text trimmed, {@code ""} for an absent or blank value */
    public static @NonNull String trimmed(@Nullable Object raw) {
        String text = Texts.trimmedOrNull(raw);
        return text == null ? "" : text;
    }

    /** @return the value when it is a String with non-whitespace content (untrimmed), else null */
    public static @Nullable String nonBlankString(@Nullable Object raw) {
        return raw instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * The value as an Integer: a whole number of any Number type or text, never a truncated fraction.
     *
     * AIDEV-NOTE: THE whole-number rule (DD11b): 512.0 reads as 512, while 512.5 and out-of-range values read as
     * absent so the caller falls back to its default instead of silently using a number nobody typed.
     *
     * @return the integer, or null for an absent, blank, fractional, out-of-range or malformed value
     */
    public static @Nullable Integer parsedInt(@Nullable Object raw) {
        PrimitiveCoercion.Result<Integer> coerced = PrimitiveCoercion.toInteger(raw,
            PrimitiveCoercion.NumberRule.EXACT_VALUE, PrimitiveCoercion.TextRule.TRIMMED_BLANK_IS_NULL);
        return coerced.ok() ? coerced.value() : null;
    }

    /** @return the {@link #parsedInt} value, or the fallback when it reads as absent */
    public static int intOr(@Nullable Object raw, int fallback) {
        return Objects.requireNonNullElse(parsedInt(raw), fallback);
    }

    /** @return the {@link #parsedInt} value when it is above zero, else null (a positive whole-number setting) */
    public static @Nullable Integer positiveInt(@Nullable Object raw) {
        Integer value = parsedInt(raw);
        return value != null && value > 0 ? value : null;
    }

    /** @return the value as a finite Double, or null for an absent, blank, non-finite or malformed value */
    public static @Nullable Double parsedDouble(@Nullable Object raw) {
        PrimitiveCoercion.Result<Double> coerced = PrimitiveCoercion.toDouble(raw, true,
            PrimitiveCoercion.TextRule.TRIMMED_BLANK_IS_NULL);
        return coerced.ok() ? coerced.value() : null;
    }

    /**
     * THE boolean read of a settings or values map: a stored Boolean is itself, anything else is the absent answer.
     *
     * @param absent what a missing, null or non-Boolean entry means
     */
    public static boolean isOn(@Nullable Map<String, ?> values, @NonNull String key, boolean absent) {
        return values != null && values.get(key) instanceof Boolean flag ? flag : absent;
    }

    /** @return the field's entry as {@link #isOn(Map, String, boolean)} reads it, absent meaning its declared default */
    public static boolean isOn(@Nullable Map<String, ?> values, @NonNull BooleanField field) {
        return isOn(values, field.getName(), Boolean.TRUE.equals(field.getDefaultValue()));
    }

    /** @return the value itself (not a copy) as a string-keyed map, or an empty map when it is not a map */
    public static @NonNull Map<String, Object> map(@Nullable Object raw) {
        return Objects.requireNonNullElse(mapOrNull(raw), Map.of());
    }

    /** @return the value itself (not a copy) as a string-keyed map, or null when it is not a map */
    @SuppressWarnings("unchecked")
    public static @Nullable Map<String, Object> mapOrNull(@Nullable Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** @return the value itself as a list, or an empty list when it is not a list */
    public static @NonNull List<?> list(@Nullable Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }
}
