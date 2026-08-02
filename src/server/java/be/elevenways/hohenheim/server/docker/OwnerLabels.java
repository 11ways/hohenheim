package be.elevenways.hohenheim.server.docker;

import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * THE producer and parser of the record-ownership labels every Docker resource
 * hohenheim creates must carry: scope ({@code owner.model}) plus discriminator
 * ({@code owner.id}), two labels, never one parsed string.
 *
 * AIDEV-NOTE: The values deliberately mirror how RecordGrants keys records
 * (Identifier.toString() for the model, String.valueOf for the id), because
 * ownership in this product is grant-derived: a labelled resource resolves to an
 * owner through the ONE existing authority (the record's manage-grant subject
 * set), never through a second owner column. Do not add a third spelling.
 */
public final class OwnerLabels {

    /** Label carrying the owning record's model identifier, e.g. {@code hohenheim:site}. */
    public static final String MODEL = "be.elevenways.hohenheim.owner.model";

    /** Label carrying the owning record's primary key, stringified. */
    public static final String ID = "be.elevenways.hohenheim.owner.id";

    /** A parsed owner claim: which record of which model created the resource. */
    public record Owner(@NonNull Identifier model, @NonNull String id) {}

    private OwnerLabels() {}

    /** The two owner labels for one record. */
    public static @NonNull Map<String, String> of(@NonNull Identifier model, @NonNull Object recordId) {
        return Map.of(MODEL, model.toString(), ID, String.valueOf(recordId));
    }

    /**
     * @return the owner claim carried by a Docker Labels map, or null when either
     *         label is absent, blank, or the model is not an explicit {@code ns:path}
     *         (Identifier.tryParse would silently invent a default namespace)
     */
    public static @Nullable Owner parse(@Nullable Map<?, ?> labels) {
        if (labels == null) {
            return null;
        }
        Object model = labels.get(MODEL);
        Object id = labels.get(ID);
        if (!(model instanceof String modelText) || !(id instanceof String idText)
            || modelText.indexOf(Identifier.NAMESPACE_SEPARATOR) <= 0 || idText.isBlank()) {
            return null;
        }
        Identifier parsed = Identifier.tryParse(modelText);
        if (parsed == null) {
            return null;
        }
        return new Owner(parsed, idText);
    }
}
