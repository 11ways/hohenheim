package be.elevenways.hohenheim.server.docker;

import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
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
     * Remove a same-named container ONLY when the daemon attributes it to this record
     * via its owner labels; an absent container is a verified no-op.
     *
     * AIDEV-NOTE: this is the fix for the legacy replace paths' worst habit -- a bare
     * force-remove of whatever holds the name, which let any tier destroy any other
     * tier's (or a stranger's) container on a name collision. The refusal is loud and
     * names the actual owner; the reconciler's FOREIGN_COLLIDING findings surface the
     * same hazard from the other side. StackDeployer has its own equivalent refusal
     * (stack-name label + declared adoption); this covers the record-owned tiers.
     *
     * @param recordId the record that must own the container; null (record-less callers:
     *                 tests, previews) can attribute nothing and refuses ANY existing container
     * @return true when a container was removed, false when none existed
     * @throws IOException when the container exists but is not attributably ours, or the
     *                     daemon cannot be asked
     */
    public static boolean removeIfOwnedBy(@NonNull DockerClient docker, @NonNull String containerName,
                                          @NonNull Identifier model, @Nullable Object recordId)
            throws IOException {
        Map<String, Object> inspect;
        try {
            inspect = docker.inspectContainer(containerName);
        } catch (DockerClient.ApiException e) {
            if (e.isNotFound()) {
                return false;   // observed absent
            }
            throw e;
        }
        Object config = inspect.get("Config");
        Owner owner = parse(config instanceof Map<?, ?> c && c.get("Labels") instanceof Map<?, ?> l
            ? l : null);
        boolean ours = owner != null && recordId != null && owner.model().equals(model)
            && owner.id().equals(String.valueOf(recordId));
        if (!ours) {
            throw new IOException("REFUSED to remove container '" + containerName + "': it is not"
                + " attributably ours (" + (owner != null
                    ? "owned by " + owner.model() + " #" + owner.id()
                    : "no hohenheim owner labels")
                + (recordId == null ? "; this caller has no record identity to match" : "")
                + "). A same-named foreign container is a name collision, not a replace target;"
                + " remove it explicitly if it is debris.");
        }
        docker.removeContainer(containerName, true);
        return true;
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
