package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.DomainRefusals;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The refusals Hohenheim answers in its own words: the uniform "not permitted" that never names the missing
 * capability, a dependency that is not ready yet, a build hook shaped after it was revoked, a stored dump a
 * restore cannot use, a database a dump or restore already holds, an unsafe dump staging area, a restore that
 * failed after it began, and an instance backup a restore cannot use where or how it was asked.
 *
 * AIDEV-NOTE: each {@link #code()} is today's {@code /api/v1} wire code, the violation key {@code ApiConduits.refusal}
 * writes, and each message is the same violations copy the service gates throw, so the API answers a refusal of the
 * operation pipeline byte-identical to the service's {@code Violations} of the same key.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@BlastAutoLoad
public enum HohenheimRefusalReason implements DomainRefusal.Reason {

    /** The caller lacks the instance capability; the same answer as for an instance it cannot see. */
    INSTANCE_NOT_PERMITTED("instance_not_permitted", DomainRefusal.Recovery.NEVER,
        "You are not allowed to perform this action on this instance"),

    /** An attached managed database is not active yet; deploying now would start the workload without it. */
    DATABASE_NOT_READY("database_not_ready", DomainRefusal.Recovery.AFTER_WAIT,
        "A database this instance uses is not ready yet"),

    /** The build hook was revoked, possibly by a request that raced this one; a revoked hook stays revoked. */
    BUILD_HOOK_REVOKED("build_hook_already_revoked", DomainRefusal.Recovery.NEVER,
        "This build hook is revoked; create a new one instead."),

    /** A stored dump restores only into a database of the engine it was taken from. */
    DATABASE_DUMP_KIND_MISMATCH("database_dump_kind_mismatch", DomainRefusal.Recovery.NEVER,
        "This dump was taken from a database of another kind"),

    /** The stored dump is not complete, or its artifact is missing or does not match its recorded checksum. */
    DATABASE_DUMP_UNUSABLE("database_dump_unusable", DomainRefusal.Recovery.NEVER,
        "This dump cannot be restored"),

    /** An in-place restore needs the dump's database, which no longer exists. */
    DATABASE_DUMP_SOURCE_GONE("database_dump_source_gone", DomainRefusal.Recovery.NEVER,
        "The database this dump was taken from no longer exists"),

    /**
     * A dump, restore, destroy, move or provisioning of the database is already running; only the nightly backup and
     * a background provisioning queue behind one.
     */
    DATABASE_BUSY("database_busy", DomainRefusal.Recovery.AFTER_WAIT,
        "A backup, restore or provisioning of this database is running"),

    /** A dump that creates or connects to databases of its own cannot be loaded into the one database it targets. */
    DATABASE_DUMP_WHOLE_SERVER("database_dump_whole_server", DomainRefusal.Recovery.NEVER,
        "A dump made with --create or pg_dumpall cannot be restored into one database"),

    /** A plaintext dump passes through the staging area, so one its owner cannot keep to itself refuses the work. */
    DATABASE_STAGING_UNSAFE("database_staging_unsafe", DomainRefusal.Recovery.NEVER,
        "The dump staging directory cannot be restricted to its owner"),

    /**
     * A restore failed after it began: its words say whether the database is as it was or may be mixed, then naming
     * the pre-restore backup to recover from, or that the half-restored new database could not be removed.
     */
    DATABASE_RESTORE_FAILED("database_restore_failed", DomainRefusal.Recovery.NEVER,
        "The restore failed"),

    /** A restore needs the backup's instance, which no longer exists or which the caller can no longer reach. */
    BACKUP_SOURCE_GONE("backup_source_gone", DomainRefusal.Recovery.NEVER,
        "The instance this backup was taken from no longer exists"),

    /**
     * A tenant's restore-to-new is bound to its instance's approved template, and this instance or backup names none,
     * or another one.
     */
    BACKUP_RESTORE_TEMPLATE_REQUIRED("backup_restore_template_required", DomainRefusal.Recovery.NEVER,
        "This backup can only be restored by an operator: its instance has no approved template"),

    /** A tenant's restore-to-new lands on its instance's own host, which cannot take it; the words say why. */
    BACKUP_RESTORE_HOST_REFUSED("backup_restore_host_refused", DomainRefusal.Recovery.AFTER_WAIT,
        "The host of this instance cannot take the restored copy right now"),

    /** A whole-machine export, an application or a backup carrying host volumes restores only to a new instance. */
    BACKUP_RESTORE_IN_PLACE_UNSUPPORTED("backup_restore_in_place_unsupported", DomainRefusal.Recovery.NEVER,
        "This backup cannot be restored in place; restore it as a new instance instead"),

    /** The backup carries a volume its instance no longer declares, so an in-place restore would put it nowhere. */
    BACKUP_VOLUMES_CHANGED("backup_volumes_changed", DomainRefusal.Recovery.NEVER,
        "The instance's volumes changed since this backup; restore it as a new instance instead"),

    /**
     * A restore's run was refused by one of the restore's own checks (the archive, the host, capacity, the create
     * funnel): the run carries that check's words.
     */
    BACKUP_RESTORE_REFUSED("backup_restore_refused", DomainRefusal.Recovery.NEVER,
        "The restore was refused");

    static {
        DomainRefusals.declare(values());
    }

    private final @NonNull Identifier id;
    private final @NonNull String code;
    private final DomainRefusal.@NonNull Recovery recovery;
    private final @NonNull Microcopy message;

    HohenheimRefusalReason(@NonNull String key, DomainRefusal.@NonNull Recovery recovery, @NonNull String fallback) {
        this.id = HohenheimIds.id(key);
        this.code = key;
        this.recovery = recovery;
        this.message = HohenheimMicrocopy.VIOLATIONS.of(key).withFallback(fallback);
    }

    @Override
    public @NonNull Identifier id() {
        return this.id;
    }

    /** @return 422, the status {@code /api/v1} answers every instance-tier refusal with */
    @Override
    public int status() {
        return 422;
    }

    @Override
    public @NonNull String code() {
        return this.code;
    }

    @Override
    public @NonNull Microcopy message() {
        return this.message;
    }

    @Override
    public DomainRefusal.@NonNull Recovery recovery() {
        return this.recovery;
    }
}
