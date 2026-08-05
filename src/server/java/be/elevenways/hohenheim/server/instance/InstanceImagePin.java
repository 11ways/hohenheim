package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Couples the pinned resolved image fingerprint to the DECLARED image: any write that
 * changes kind, image or tag clears the pin, on every writer (form, API, revision
 * restore, direct save) -- otherwise an absent-workload recreate would silently
 * revive the OLD image while the record claims the new one.
 */
public final class InstanceImagePin {

    private static volatile boolean installed;

    private InstanceImagePin() {
    }

    /** Install the invalidation hook; idempotent, called at the MODULES boot stage. */
    public static synchronized void installInvalidation() {
        if (installed) {
            return;
        }
        installed = true;
        InstanceModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(InstanceModel.ID.getName())
                    || row.get(InstanceModel.ID) == null) {
                return;   // a create has no pin to invalidate
            }
            Row stored = Models.get(InstanceModel.class).findById(row.get(InstanceModel.ID));
            if (stored == null || stored.get(InstanceModel.IMAGE_FINGERPRINT) == null) {
                return;
            }
            Object kind = effective(row, stored, InstanceModel.KIND.getName());
            String image = imageOf(effective(row, stored, InstanceModel.SETTINGS.getName()), "image");
            String tag = imageOf(effective(row, stored, InstanceModel.SETTINGS.getName()), "tag");
            boolean unchanged = Objects.equals(kind, stored.get(InstanceModel.KIND))
                && Objects.equals(image, imageOf(stored.get(InstanceModel.SETTINGS), "image"))
                && Objects.equals(tag, imageOf(stored.get(InstanceModel.SETTINGS), "tag"));
            if (!unchanged) {
                row.set(InstanceModel.IMAGE_FINGERPRINT, null);
            }
        });
    }

    private static @Nullable Object effective(@NonNull Row row, @NonNull Row stored,
                                              @NonNull String name) {
        return row.has(name) ? row.get(name) : stored.get(name);
    }

    private static @Nullable String imageOf(@Nullable Object settings, @NonNull String key) {
        if (settings instanceof Map<?, ?> map && map.get(key) != null) {
            String value = String.valueOf(map.get(key)).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }
}
