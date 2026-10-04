package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

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
            Row stored = StoredRows.byId(Models.get(InstanceModel.class), row.get(InstanceModel.ID));
            if (stored == null || stored.get(InstanceModel.IMAGE_FINGERPRINT) == null) {
                return;
            }
            Object kind = row.afterWrite(InstanceModel.KIND, stored);
            Object settings = row.afterWrite(InstanceModel.SETTINGS, stored);
            Object storedSettings = stored.get(InstanceModel.SETTINGS);
            boolean unchanged = Objects.equals(kind, stored.get(InstanceModel.KIND))
                && Objects.equals(InstanceImagePolicy.settingText(settings, "image"),
                    InstanceImagePolicy.settingText(storedSettings, "image"))
                && Objects.equals(InstanceImagePolicy.settingText(settings, "tag"),
                    InstanceImagePolicy.settingText(storedSettings, "tag"));
            if (!unchanged) {
                row.set(InstanceModel.IMAGE_FINGERPRINT, null);
            }
        });
    }
}
