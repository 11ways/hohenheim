package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.instance.ImageOrigin;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.text.Texts;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * THE image gate of the threat model: templates are the DEFAULT source of instance
 * images -- a tenant-originated write may only run an image an APPROVED template
 * declares (via the instance's {@code template_id}), and anything else requires the
 * {@code image_any} record capability, which is exec-equivalent and admin/type-level
 * by doctrine.
 *
 * Enforced on the model write pipeline (the TenantWrites posture): the resource layer
 * is a UX affordance, never a gate -- revision restore, direct saves and any future
 * /manage flow all reach the datasource without passing a resource method. Operators,
 * background tasks and seeds are system work and pass untouched.
 *
 * AIDEV-NOTE: the gate judges the CHANGE, not the state -- it fires only when the
 * effective (staged-else-stored) kind, image, tag or template_id would DIFFER from the
 * stored row. Without that, a tenant renaming an operator-authored arbitrary-image
 * instance would be refused for an image fact the rename never touched.
 */
public final class InstanceImagePolicy {

    /** How one judged settings member is READ out of a settings map. */
    @FunctionalInterface
    public interface SettingReading {
        @Nullable String read(@Nullable Object settings);
    }

    /** One judged {@code settings} member: its key and how to read it for comparison. */
    public record JudgedSetting(@NonNull String key, @NonNull SettingReading reading) {}

    /**
     * The {@code settings} members this gate judges, WITH the reading each comparison uses
     * -- one declaration that both {@link #JUDGED_SETTINGS_KEYS} and {@link #check} are
     * derived from.
     *
     * AIDEV-NOTE: until 2026-08-17 the key SET was a literal and {@link #check} read
     * image/tag/image_origin by hand three times each, and the javadoc openly admitted the
     * hole: "adding a member here without teaching check to judge it re-opens exactly
     * that". It is not possible to add one here without judging it now -- the comparisons
     * iterate THIS list. That matters because {@code TenantWrites.checkInstanceWrite}
     * freezes every settings key NOT in the exported set, so a key this gate does not
     * judge and TenantWrites does not freeze is a key with NO authority check on it (that
     * was {@code privileged}, which lowers straight onto an Incus
     * {@code security.privileged} container).
     */
    private static final List<JudgedSetting> JUDGED_SETTINGS = List.of(
        new JudgedSetting(InstanceKindFields.IMAGE, settings -> settingText(settings, InstanceKindFields.IMAGE)),
        new JudgedSetting("tag", settings -> settingText(settings, "tag")),
        // Absent origin is CATALOG on both sides -- the pre-existing default
        // (InstanceSpec's convenience constructor), so an approved template authored
        // before image_origin existed keeps authorising exactly what it always did.
        new JudgedSetting(ImageOrigin.SETTING, InstanceImagePolicy::originOf));

    /** The judged keys, and therefore the ONLY settings members a tenant write may move. */
    public static final Set<String> JUDGED_SETTINGS_KEYS = JUDGED_SETTINGS.stream()
        .map(JudgedSetting::key)
        .collect(Collectors.toUnmodifiableSet());

    private static volatile boolean installed;

    private InstanceImagePolicy() {
    }

    /** Install the write-funnel gate; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        // beforeValidate, like every TenantWrites invariant: it runs before the quota
        // hook's beforeWrite reservation, so a refused image never spends a slot.
        InstanceModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && TenantWrites.isTenantOriginated()) {
                check(row);
            }
        });
    }

    private static void check(@NonNull Row row) {
        Row stored = StoredRows.of(Models.get(InstanceModel.class), row);

        Object templateId = row.afterWrite(InstanceModel.TEMPLATE_ID, stored);
        Object kind = row.afterWrite(InstanceModel.KIND, stored);
        Map<String, String> judged =
            readJudged(row.afterWrite(InstanceModel.SETTINGS, stored));
        String image = judged.get(InstanceKindFields.IMAGE);

        if (stored != null) {
            boolean unchanged = Objects.equals(templateId, stored.get(InstanceModel.TEMPLATE_ID))
                && Objects.equals(kind, stored.get(InstanceModel.KIND))
                && judged.equals(readJudged(stored.get(InstanceModel.SETTINGS)));
            if (unchanged) {
                return;   // the write never touched an image fact; not this gate's business
            }
        }

        // Path 1: the instance rides a template. The template must exist, be APPROVED,
        // and declare exactly this kind and every judged image fact -- a prepared alias
        // namespace is entirely operator-controlled, so an approved CATALOG template must
        // never authorise a same-named PREPARED alias (or vice versa).
        if (templateId instanceof Integer id) {
            Row template = Models.get(InstanceTemplateModel.class).findById(id);
            if (template != null && template.get(InstanceTemplateModel.APPROVED_AT) != null
                    && Objects.equals(kind, template.get(InstanceTemplateModel.KIND))
                    && judged.equals(readJudged(template.get(InstanceTemplateModel.SETTINGS)))) {
                return;
            }
        }

        // Path 2: anything else is an ARBITRARY image and needs image_any ON THE RECORD.
        // A create has no record yet, so a tenant create can never pass this branch --
        // tenant creation is create-from-approved-template by construction.
        AccessContext ctx = TenantWrites.acting();
        Integer recordId = stored != null ? stored.get(InstanceModel.ID) : null;
        if (ctx != null && recordId != null
                && ctx.hasCapability(InstanceModel.MODEL_ID, recordId, HohenheimCapabilities.IMAGE_ANY)) {
            return;
        }
        throw Violations.ofField("settings.image", image,
            HohenheimMicrocopy.VIOLATIONS.of("image_requires_capability")
                .withArg("image", image == null ? "" : image));
    }

    /**
     * Every judged member of one settings map, keyed by name.
     *
     * @return a map carrying an entry per {@link #JUDGED_SETTINGS} member, null values
     *         included, so two readings compare with a single {@code equals}
     */
    private static @NonNull Map<String, String> readJudged(@Nullable Object settings) {
        Map<String, String> readings = new LinkedHashMap<>();
        for (JudgedSetting judged : JUDGED_SETTINGS) {
            readings.put(judged.key(), judged.reading().read(settings));
        }
        return readings;
    }

    /** @return one kind setting of a settings value as trimmed text, null when absent or blank */
    static @Nullable String settingText(@Nullable Object settings, @NonNull String key) {
        return settings instanceof Map<?, ?> map ? Texts.trimmedOrNull(map.get(key)) : null;
    }

    /** The declared {@code image_origin} key, defaulting to catalog like {@link ImageOrigin}. */
    private static @NonNull String originOf(@Nullable Object settings) {
        String key = settingText(settings, ImageOrigin.SETTING);
        return key == null ? ImageOrigin.CATALOG.key() : key;
    }
}
