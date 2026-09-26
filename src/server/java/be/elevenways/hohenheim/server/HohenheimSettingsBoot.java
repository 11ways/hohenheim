package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.setting.SettingGroup;
import be.elevenways.zenit.server.ServerZenitRuntime;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Loads the framework's default settings chain, which carries Hohenheim's {@code hohenheim.*} group, and captures the
 * role snapshot.
 */
public final class HohenheimSettingsBoot {

    private HohenheimSettingsBoot() {
    }

    /**
     * Loads {@code settings/default.dry}, {@code settings/local.dry} and {@code ZENIT__*} early, adopting a retired
     * {@code settings/hohenheim.dry} into local.dry first (HohenheimRetiredNames), then captures the role snapshot.
     */
    public static void load() {
        forceDefinitions();
        applyFrameworkDefaults();
        ServerZenitRuntime.loadDefaultSettings();
        // The settings just became real: this is THE role-snapshot moment.
        // Every roles.* gate reads the snapshot, never the live setting.
        HohenheimRoles.capture();
    }

    /** Hohenheim's own default for activity retention, in days. */
    public static final int ACTIVITY_RETENTION_DAYS = 90;

    /**
     * Hohenheim's defaults for FRAMEWORK settings it depends on, applied only where nothing
     * set the key yet.
     *
     * AIDEV-NOTE: activity pruning is zenit's own ActivityPruneTask reading
     * activity.retention_days, whose framework default is 0 (keep forever). Hohenheim kept
     * 90 days through a task of its own (CleanOldActivity, deleted 2026-09-23), so the
     * behaviour must not change on upgrade: this seeds 90. It runs BEFORE the framework
     * chain loads at boot ({@link #load}), so settings/local.dry or
     * ZENIT__ACTIVITY__RETENTION_DAYS still override it; a value already loaded is kept.
     */
    static void applyFrameworkDefaults() {
        if (!Zenit.SETTINGS_VALUES.hasValue(ActivityLog.RETENTION_DAYS)) {
            Zenit.SETTINGS_VALUES.setValue(ActivityLog.RETENTION_DAYS, ACTIVITY_RETENTION_DAYS);
        }
    }

    /**
     * Define every nested settings group BEFORE values load, since undefined keys are
     * dropped at load time and the migrate-only path runs before the protoblast autoload
     * loader is guaranteed to have fired.
     *
     * AIDEV-NOTE: DERIVED from the declared groups, never a hand-written list. The list
     * this replaced had silently drifted -- it omitted Stacks, so every {@code stacks.*}
     * key in the settings file was dropped on the migrate-only path. A hand-maintained
     * mirror of a declared set only ever drifts again; adding a group must be enough.
     * Reading the field also runs the nested class's initializer, which is the whole
     * point. Server-only reflection over our OWN class, which the source-set rules allow.
     *
     * @return the group names this loader guarantees are defined
     */
    public static @NonNull Set<String> forceDefinitions() {
        Set<String> forced = new LinkedHashSet<>();
        for (Class<?> nested : HohenheimSettings.class.getDeclaredClasses()) {
            Object value;
            try {
                value = nested.getDeclaredField("GROUP").get(null);
            } catch (NoSuchFieldException notAGroupHolder) {
                continue;
            } catch (IllegalAccessException unreachable) {
                throw new IllegalStateException(
                    "Cannot read " + nested.getSimpleName() + ".GROUP", unreachable);
            }
            if (value instanceof SettingGroup group) {
                forced.add(group.getName());
            }
        }
        return forced;
    }
}
