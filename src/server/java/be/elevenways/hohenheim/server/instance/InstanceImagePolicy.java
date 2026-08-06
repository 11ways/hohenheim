package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;

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
        Row stored = storedOf(row);

        Object templateId = effective(row, stored, InstanceModel.TEMPLATE_ID.getName());
        Object kind = effective(row, stored, InstanceModel.KIND.getName());
        String image = imageOf(effective(row, stored, InstanceModel.SETTINGS.getName()), "image");
        String tag = imageOf(effective(row, stored, InstanceModel.SETTINGS.getName()), "tag");
        // Absent origin is CATALOG on both sides -- this is the pre-existing default
        // (InstanceSpec's convenience constructor), so an approved template authored
        // before image_origin existed keeps authorising exactly what it always did.
        String origin = originOf(effective(row, stored, InstanceModel.SETTINGS.getName()));

        if (stored != null) {
            boolean unchanged = Objects.equals(templateId, stored.get(InstanceModel.TEMPLATE_ID))
                && Objects.equals(kind, stored.get(InstanceModel.KIND))
                && Objects.equals(image, imageOf(stored.get(InstanceModel.SETTINGS), "image"))
                && Objects.equals(tag, imageOf(stored.get(InstanceModel.SETTINGS), "tag"))
                && Objects.equals(origin, originOf(stored.get(InstanceModel.SETTINGS)));
            if (unchanged) {
                return;   // the write never touched an image fact; not this gate's business
            }
        }

        // Path 1: the instance rides a template. The template must exist, be APPROVED,
        // and declare exactly this kind, image AND origin -- a prepared alias namespace
        // is entirely operator-controlled, so an approved CATALOG template must never
        // authorise a same-named PREPARED alias (or vice versa).
        if (templateId instanceof Integer id) {
            Row template = Models.get(InstanceTemplateModel.class).findById(id);
            if (template != null && template.get(InstanceTemplateModel.APPROVED_AT) != null
                    && Objects.equals(kind, template.get(InstanceTemplateModel.KIND))
                    && Objects.equals(image, imageOf(template.get(InstanceTemplateModel.SETTINGS), "image"))
                    && Objects.equals(tag, imageOf(template.get(InstanceTemplateModel.SETTINGS), "tag"))
                    && Objects.equals(origin, originOf(template.get(InstanceTemplateModel.SETTINGS)))) {
                return;
            }
        }

        // Path 2: anything else is an ARBITRARY image and needs image_any ON THE RECORD.
        // A create has no record yet, so a tenant create can never pass this branch --
        // tenant creation is create-from-approved-template by construction.
        AccessContext ctx = TenantWrites.acting();
        Integer recordId = stored != null ? stored.get(InstanceModel.ID) : null;
        if (ctx != null && recordId != null
                && ctx.hasCapability(InstanceModel.MODEL_ID, recordId, HohenheimAccess.IMAGE_ANY)) {
            return;
        }
        throw Violations.ofField("settings.image", image,
            Microcopy.of("image_requires_capability").withFilter("scope", "violations")
                .withArg("image", image == null ? "" : image));
    }

    /** The staged value when the write carries the column, else the stored one. */
    private static @Nullable Object effective(@NonNull Row row, @Nullable Row stored,
                                              @NonNull String name) {
        if (row.has(name)) {
            return row.get(name);
        }
        return stored != null ? stored.get(name) : null;
    }

    private static @Nullable String imageOf(@Nullable Object settings, @NonNull String key) {
        if (settings instanceof Map<?, ?> map && map.get(key) != null) {
            String value = String.valueOf(map.get(key)).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /** The declared {@code image_origin} key, defaulting to catalog like {@link ImageOrigin}. */
    private static @NonNull String originOf(@Nullable Object settings) {
        String key = imageOf(settings, "image_origin");
        return key == null ? ImageOrigin.CATALOG.key() : key;
    }

    private static @Nullable Row storedOf(@NonNull Row row) {
        if (!row.has(InstanceModel.ID.getName()) || row.get(InstanceModel.ID) == null) {
            return null;
        }
        return Models.get(InstanceModel.class).findById(row.get(InstanceModel.ID));
    }
}
