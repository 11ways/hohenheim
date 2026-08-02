package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The boot-time snapshot of the {@code roles.*} settings: every role gate in the
 * process reads THIS, never the live setting.
 *
 * AIDEV-NOTE: restartRequired on a setting is advisory-only (nothing enforces it
 * at runtime), so consistency comes from reading the flags exactly once. The
 * snapshot is captured by {@link HohenheimSettingsFiles#load()} -- the moment the
 * settings become real -- and a read before capture throws instead of silently
 * answering from defaults, because a gate that quietly defaults to "enabled"
 * (or "disabled") is how a role flag turns into security theater.
 */
public final class HohenheimRoles {

    /** The subsystems an installation can opt out of. */
    public enum Role {
        PROXY(HohenheimSettings.Roles.PROXY),
        DNS(HohenheimSettings.Roles.DNS),
        FIREWALL(HohenheimSettings.Roles.FIREWALL),
        STACKS(HohenheimSettings.Roles.STACKS),
        PROCESSES(HohenheimSettings.Roles.PROCESSES),
        DATABASES(HohenheimSettings.Roles.DATABASES);

        private final SettingDefinition<Boolean> setting;

        Role(SettingDefinition<Boolean> setting) {
            this.setting = setting;
        }

        /** The lowercase settings-key token, e.g. "proxy" for roles.proxy. */
        public @NonNull String token() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static volatile @Nullable Set<Role> snapshot;

    private HohenheimRoles() {
    }

    /**
     * Snapshot the current {@code roles.*} values. Idempotent-latest: a repeat call
     * re-reads, which only ever happens from a repeated settings load.
     */
    public static synchronized void capture() {
        EnumSet<Role> enabled = EnumSet.noneOf(Role.class);
        for (Role role : Role.values()) {
            if (Boolean.TRUE.equals(HohenheimSettings.VALUES.getValue(role.setting))) {
                enabled.add(role);
            }
        }
        snapshot = Set.copyOf(enabled);
        Blast.slog("hohenheim.roles_captured", Map.of(
            "enabled", enabled.stream().map(Role::token).sorted().toList()));
    }

    public static boolean isCaptured() {
        return snapshot != null;
    }

    /** @throws IllegalStateException when read before the boot snapshot exists */
    public static boolean enabled(@NonNull Role role) {
        return requireSnapshot().contains(role);
    }

    /** @return true when at least one of the given roles is enabled */
    public static boolean anyEnabled(@NonNull Role... roles) {
        Set<Role> current = requireSnapshot();
        for (Role role : roles) {
            if (current.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The shared schedule gate for role-owned tasks: the declarations when any of the
     * given roles is enabled, an explicit retraction (empty list) otherwise.
     */
    public static @NonNull List<ScheduleDeclaration> schedulesWhen(
            @NonNull List<ScheduleDeclaration> declarations, @NonNull Role... roles) {
        return anyEnabled(roles) ? declarations : List.of();
    }

    private static @NonNull Set<Role> requireSnapshot() {
        Set<Role> current = snapshot;
        if (current == null) {
            throw new IllegalStateException("HohenheimRoles read before capture:"
                + " HohenheimSettingsFiles.load() must run first");
        }
        return current;
    }

    /** Test seam: drop the snapshot so the next load re-captures. */
    public static synchronized void resetForTesting() {
        snapshot = null;
    }
}
