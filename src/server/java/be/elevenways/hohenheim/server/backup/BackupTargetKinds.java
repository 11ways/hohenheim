package be.elevenways.hohenheim.server.backup;

import be.elevenways.hohenheim.backup.BackupTargetRegistry;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.Map;

/**
 * Registration hook for the compile-time-discovered backup-target kinds plus the
 * record-to-target resolution every backup operation funnels through.
 */
public final class BackupTargetKinds {

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups work
     * regardless of which class the JVM touched first. MUST be the LAST static field.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private BackupTargetKinds() {}

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(BackupTargetKindHandler handler) {
        BackupTargetRegistry.REGISTRY.add(handler.typeId(), handler);
    }

    /**
     * The server half of a stored kind token, read out of THE registry.
     *
     * AIDEV-NOTE: there used to be a private handler map beside the registry, filled by the
     * same register call: two homes for one vocabulary. A registry entry that is not a
     * server handler (a common-only info) fails closed as "unknown kind".
     */
    public static @Nullable BackupTargetKindHandler getHandler(@Nullable String typeIdentifier) {
        if (typeIdentifier == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(typeIdentifier);
        return id != null && BackupTargetRegistry.REGISTRY.get(id) instanceof BackupTargetKindHandler handler
            ? handler : null;
    }

    /**
     * Resolve a configured backup-target record to its live {@link BackupTarget}.
     *
     * @throws Violations naming the problem (missing record, unknown kind, bad settings)
     */
    public static @NonNull BackupTarget targetFor(@Nullable Integer targetId) {
        Row row = targetId == null ? null
            : Models.get(BackupTargetModel.class).findById(targetId);
        if (row == null) {
            throw Violations.ofForm(violation("backup_target_missing"));
        }
        return targetOf(row);
    }

    /** Resolve a loaded backup-target row (admin test-connection action). */
    public static @NonNull BackupTarget targetOf(@NonNull Row row) {
        BackupTargetKindHandler handler = getHandler(row.get(BackupTargetModel.KIND));
        if (handler == null) {
            throw Violations.ofField("kind", row.get(BackupTargetModel.KIND),
                violation("backup_target_kind_unknown")
                    .withArg("kind", String.valueOf((Object) row.get(BackupTargetModel.KIND))));
        }
        Map<String, Object> settings = row.get(BackupTargetModel.SETTINGS) instanceof Map<?, ?> map
            ? castSettings(map) : Map.of();
        try {
            return handler.targetFor(settings);
        } catch (IOException bad) {
            throw Violations.ofForm(violation("backup_target_invalid")
                .withArg("name", String.valueOf((Object) row.get(BackupTargetModel.NAME)))
                .withArg("reason", bad.getMessage() != null ? bad.getMessage() : bad.toString()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSettings(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
