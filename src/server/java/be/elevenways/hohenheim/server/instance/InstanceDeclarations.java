package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.source.GitRepository;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * The instance declarations that must be refused AT THE WRITE, on the form the operator
 * submitted them on, rather than hours later on a deploy nobody is watching.
 *
 * AIDEV-NOTE: both rules here already existed further down the road -- {@code
 * RuntimeImages.requireFor} refuses an imageless workload at deploy, and
 * {@code GitRepository} refuses a credentialed clone URL when it runs git. Neither is
 * removed: this hook is the EARLY half, and it deliberately throws the SAME named
 * refusal so the operator reads one sentence whichever moment catches it. A record that
 * got past this hook before it existed still meets the late half.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceDeclarations {

    private static boolean installed;

    private InstanceDeclarations() {
    }

    /** Install the declaration refusals on the instance write funnel (MODULES stage). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        InstanceModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = storedOf(row);
            // Only a write that leaves the record LIVE has a declaration to judge: a soft
            // delete of a record that never named an image must not be refused for the
            // very thing it is undoing.
            if (effectiveDeletedAt(row, stored) != null) {
                return;
            }
            Violations refused = judge(row, stored);
            if (!refused.isEmpty()) {
                throw refused;
            }
        });
    }

    /**
     * Every declaration refusal the row would meet at the write, COLLECTED rather than
     * thrown at the first one, so a form shows all of them in one pass.
     *
     * AIDEV-NOTE: this is the one judgment; the write hook throws it and the CMS create
     * lane ({@code InstanceResource.persistRow}) merges it with the placement refusal
     * BEFORE the save, because the hook can only run inside the save and a refusal thrown
     * ahead of it (placement) used to hide every refusal behind it. The save re-judges an
     * already-passing row, which is cheap and keeps the hook the authority.
     *
     * @param stored the row as it is on disk, or null on a create
     */
    public static @NonNull Violations judge(@NonNull Row row, @Nullable Row stored) {
        Violations refused = new Violations();
        collect(refused, () -> requireRuntimeImage(row, stored));
        collect(refused, () -> requireUsableRepository(row));
        return refused;
    }

    private static void collect(@NonNull Violations into, @NonNull Runnable rule) {
        try {
            rule.run();
        } catch (Violations refused) {
            into.addAll(refused);
        }
    }

    /**
     * A kind that cannot deploy without a runtime image must name one.
     *
     * AIDEV-NOTE: {@code requiresRuntimeImage()} and NOT {@code usesRuntimeImage()}. The
     * second only says the picker resolves: an application MAY name a runtime image as
     * its build base and deploys perfectly well without one, so requiring it there would
     * refuse every application create. The kind carries the fact, so a kind that starts
     * requiring an image inherits this refusal with nothing wired here.
     */
    private static void requireRuntimeImage(@NonNull Row row, @Nullable Row stored) {

        InstanceKindHandler handler = InstanceKinds.getHandler(effectiveKind(row, stored));

        if (handler == null || !handler.requiresRuntimeImage()) {
            return;
        }

        if (effectiveImageId(row, stored) == null) {
            throw Violations.ofField(InstanceModel.RUNTIME_IMAGE_ID.getName(), null,
                violation("runtime_image_required"));
        }
    }

    /**
     * A raw repository URL must be something git can clone, and may not carry a credential.
     *
     * AIDEV-NOTE: the provider lane hands its token to git through the exec ENVIRONMENT
     * and nothing else, so nothing is written down; a hand-typed
     * {@code https://user:token@host/repo.git} is the one spelling that ends up in
     * {@code .git/config} inside the workspace volume and STAYS there. What counts as a
     * credential is {@link GitRepository#embeddedCredential}, and what counts as a clone
     * URL is {@link GitRepository#isSupportedCloneUrl} -- both live beside the runner that
     * actually spawns git, including the credential rule's deliberate carve-out for a bare
     * {@code ssh://user@host} username. The credential half is asked FIRST: a credentialed
     * URL is perfectly well-formed, and the operator must read the refusal that is about
     * the secret rather than one about the shape.
     */
    private static void requireUsableRepository(@NonNull Row row) {

        if (!row.has(InstanceModel.SETTINGS.getName())) {
            return;
        }

        Object declared = settingsOf(row).get(GitSourceSchema.REPOSITORY_URL);
        String url = declared == null ? "" : declared.toString().trim();

        if (url.isEmpty()) {
            return;
        }

        // A credentialed value is never echoed back: it is the secret this refusal is about.
        if (GitRepository.embeddedCredential(url) != null) {
            throw Violations.ofField(GitSourceSchema.REPOSITORY_URL, null,
                violation("repository_url_credential"));
        }

        // A malformed one is only a typo, and the operator corrects it in place.
        if (!GitRepository.isSupportedCloneUrl(url)) {
            throw Violations.ofField(GitSourceSchema.REPOSITORY_URL, url,
                violation("repository_url_invalid"));
        }
    }

    private static @Nullable String effectiveKind(@NonNull Row row, @Nullable Row stored) {
        if (row.has(InstanceModel.KIND.getName()) || stored == null) {
            return row.get(InstanceModel.KIND);
        }
        return stored.get(InstanceModel.KIND);
    }

    /**
     * The image the write will END UP naming: a partial CMS update carries only the
     * changed keys, so reading the staged row alone would see an untouched edit as a
     * cleared image (the effectiveGb idiom, InstanceRootDiskQuota).
     */
    private static @Nullable Integer effectiveImageId(@NonNull Row row, @Nullable Row stored) {
        if (row.has(InstanceModel.RUNTIME_IMAGE_ID.getName()) || stored == null) {
            return row.get(InstanceModel.RUNTIME_IMAGE_ID);
        }
        return stored.get(InstanceModel.RUNTIME_IMAGE_ID);
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> settingsOf(@NonNull Row instance) {
        return instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
    }

    private static @Nullable Object effectiveDeletedAt(@NonNull Row row, @Nullable Row stored) {
        if (row.has(InstanceModel.DELETED_AT.getName())) {
            return row.get(InstanceModel.DELETED_AT.getName());
        }
        return stored != null ? stored.get(InstanceModel.DELETED_AT) : null;
    }

    private static @Nullable Row storedOf(@NonNull Row row) {
        if (!row.has(InstanceModel.ID.getName()) || row.get(InstanceModel.ID) == null) {
            return null;
        }
        return Models.get(InstanceModel.class).findById(row.get(InstanceModel.ID));
    }

    private static @NonNull Microcopy violation(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
