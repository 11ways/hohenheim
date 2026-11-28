package be.elevenways.hohenheim.server.backup;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimStoredIds;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.StoredIdChains;
import be.elevenways.zenit.auth.cms.SubjectTag;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.PrincipalKind;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.server.storage.DeclaredStorage;
import be.elevenways.zenit.server.storage.DeclaredStorages;
import be.elevenways.zenit.server.storage.LocalFolderStorageKind;
import be.elevenways.zenit.server.storage.StorageOwner;
import be.elevenways.zenit.server.storage.StorageReach;
import be.elevenways.zenit.server.storage.Storages;
import be.elevenways.zenit.storage.sftp.server.SftpStorageKind;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * Hohenheim's source of the storages people declare: its {@link BackupTargetModel} rows, handed to the storage layer
 * through {@link DeclaredStorages#SEAM} and opened there through their kind.
 *
 * AIDEV-NOTE: a row written before storages existed keeps its stored kind and settings. Its kind resolves through
 * {@link HohenheimStoredIds#CHAIN} ({@code hohenheim:filesystem} is the local folder kind, {@code hohenheim:ssh} the
 * SFTP kind) and its settings through the config upgrades declared there, read on every use and never written back:
 * a folder's {@code path} is the folder; an SSH target's host record gives the address, the account, the client key
 * and the pinned host key, re-read and re-gated (HostAdmission) each time, so the host record stays the one trust
 * authority and a rotated key or a quarantine reaches the storage at once. The base path is unchanged, so every
 * stored key still resolves.
 *
 * AIDEV-NOTE: who owns a storage is who holds its manage grant (no owner column, the ProjectModel rule); none is the
 * operator. Only an operator's storage may reach a private address, and only where this host's boot allows it
 * ({@code storage.allow_private_networks}): there is no column for a per-storage switch. An SSH-era row is the one
 * exception: its address is the host record's, which HostAdmission admitted as a backup destination (checked again
 * on every read), so that admission decides its reach as it decided where ssh went
 * ({@link StorageReach#ADMITTED_HOST}), loopback and private hosts included, with no boot opt-in.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
@BlastAutoLoad
public final class HohenheimStorages implements DeclaredStorages {

    /** The kind backup targets were stored under while they held a directory. */
    public static final Identifier STORED_FOLDER_KIND = HohenheimIds.id("filesystem");

    /** The kind backup targets were stored under while they wrote over ssh to a host record. */
    public static final Identifier STORED_SSH_KIND = HohenheimIds.id("ssh");

    /** The setting an SSH-era row names its host record by. */
    private static final String SSH_HOST_RECORD = "server";

    /** The one instance, installed as the class loads. */
    public static final HohenheimStorages INSTANCE = new HohenheimStorages();

    /** The names this source answered, so a change of any row releases what was opened under its old name. */
    private static final Set<String> ANSWERED = ConcurrentHashMap.newKeySet();

    static {
        HohenheimStoredIds.CHAIN.renameType(STORED_FOLDER_KIND, LocalFolderStorageKind.ID);
        HohenheimStoredIds.CHAIN.upgradeConfig(LocalFolderStorageKind.ID, HohenheimStorages::folderOfPath);
        HohenheimStoredIds.CHAIN.renameType(STORED_SSH_KIND, SftpStorageKind.ID);
        HohenheimStoredIds.CHAIN.upgradeConfig(SftpStorageKind.ID, HohenheimStorages::sftpOfHostRecord);
        DeclaredStorages.SEAM.install(INSTANCE);
        BackupTargetModel.SCHEMA.addAfterSaveHook(context -> refreshAnswered());
        BackupTargetModel.SCHEMA.addAfterRemoveHook(context -> refreshAnswered());
        // A host record an SSH-era storage reaches through decides that storage's settings and its gate: a rotated key,
        // a new pin or a quarantine releases the adapter opened before, so an exchange in flight fails at once.
        HostModel.SCHEMA.addAfterSaveHook(context -> refreshAnswered());
    }

    private HohenheimStorages() {}

    /**
     * @return the storage the row named so declares, or null when none does or its SSH-era host refuses to receive
     *         backups now ({@link BackupStorages#of} names that refusal); one indexed read
     */
    @Override
    public @Nullable DeclaredStorage find(@NonNull String name) {
        Row row = Models.get(BackupTargetModel.class).find().where(BackupTargetModel.NAME.eq(name)).first();
        if (row == null) {
            return null;
        }
        ANSWERED.add(name);
        try {
            return declarationOf(row);
        } catch (RuntimeException refused) {
            // Any refusal (a host gate, a gone host, unreadable grants): nothing is declared under the name now, and
            // BackupStorages.of asks the row itself, so its caller reads the refusal in its own words.
            return null;
        }
    }

    /**
     * @return the declaration a row holds: its kind as the storage layer names it, its settings in that kind's shape
     * @throws be.elevenways.zenit.common.validation.Violations when an SSH-era row's host record cannot receive backups
     */
    public static @NonNull DeclaredStorage declarationOf(@NonNull Row row) {
        return declarationOf(row, ownerOf(row));
    }

    /**
     * @return the declaration a row holds once saved by {@code owner}: a row not yet created has no grants to read
     * @throws IllegalArgumentException when SFTP settings name a host record outside an operator's SSH-era row: the
     *                                  host record's client key is the operator's alone
     */
    public static @NonNull DeclaredStorage declarationOf(@NonNull Row row, @NonNull StorageOwner owner) {
        Identifier kind = kindOf(row);
        Map<String, Object> stored = RawValues.map(row.get(BackupTargetModel.SETTINGS));
        boolean hostRecord = SftpStorageKind.ID.equals(kind) && stored.containsKey(SSH_HOST_RECORD);
        if (hostRecord && !(owner instanceof StorageOwner.Operator
                && STORED_SSH_KIND.toString().equals(trimmed(row.get(BackupTargetModel.KIND))))) {
            // The upgrade below would hand these settings the host record's client key and pin: only a row the
            // operator stored under the SSH kind before storages existed may name one.
            throw new IllegalArgumentException("Only an operator's storage stored as an SSH backup target names a "
                + "host record");
        }
        Map<String, Object> settings = HohenheimStoredIds.CHAIN.applyConfigUpgrades(kind, stored);
        Object id = row.get(BackupTargetModel.ID);
        // An SSH-era row reaches what its host record's admission admitted (HostAdmission, just checked by the
        // upgrade); every other operator storage private networks under the boot's opt-in; a tenant's the internet.
        StorageReach reach = !(owner instanceof StorageOwner.Operator) ? StorageReach.PUBLIC_INTERNET
            : hostRecord ? StorageReach.ADMITTED_HOST : StorageReach.PRIVATE_NETWORKS;
        return new DeclaredStorage(id == null ? "new" : String.valueOf(id), trimmed(row.get(BackupTargetModel.NAME)),
            kind, settings, owner, reach);
    }

    /**
     * @return who a storage the caller creates now belongs to ({@link HohenheimAccess#creationOwnerSubjects})
     * @throws IllegalStateException when that would be the operator but the caller is no administrator and no system
     *                               work (a guest, a bearer, nobody): an operator's storage may reach private
     *                               addresses, so an unknown creator never gets one
     */
    public static @NonNull StorageOwner ownerOfCreator(@Nullable AccessContext caller) {
        Set<String> subjects = HohenheimAccess.creationOwnerSubjects(caller);
        if (subjects.isEmpty() && (caller == null
                || !(HohenheimAccess.isAdmin(caller) || caller.systemIdentity() != null))) {
            throw new IllegalStateException("Only an administrator or the system's own work declares an operator's "
                + "storage; this caller is neither and holds no account");
        }
        return ownerOfSubjects(subjects, "a new storage");
    }

    /** @return the kind the row names, a former spelling read through the stored-id chains */
    public static @NonNull Identifier kindOf(@NonNull Row row) {
        return StoredIdChains.resolve(trimmed(row.get(BackupTargetModel.KIND)));
    }

    /**
     * @return the operator when no grant subject manages the row, else the tenant holding its manage grant
     * @throws IllegalStateException when the grants cannot be read, or name no person: an owner this cannot tell is
     *         never taken for the operator
     */
    public static @NonNull StorageOwner ownerOf(@NonNull Row row) {
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(BackupTargetModel.MODEL_ID,
            (Object) row.get(BackupTargetModel.ID));
        if (subjects == null) {
            throw new IllegalStateException("The owner of storage '" + row.get(BackupTargetModel.NAME)
                + "' cannot be read");
        }
        return ownerOfSubjects(subjects, "storage '" + row.get(BackupTargetModel.NAME) + "'");
    }

    private static @NonNull StorageOwner ownerOfSubjects(@NonNull Set<String> subjects, @NonNull String what) {
        if (subjects.isEmpty()) {
            return StorageOwner.OPERATOR;
        }
        for (String token : subjects) {
            SubjectTag subject = SubjectTag.parse(token);
            PrincipalKind kind = subject == null ? null : subject.type().principalKind();
            if (kind != null) {
                return new StorageOwner.Tenant(new PrincipalRef(kind, subject.id()));
            }
        }
        throw new IllegalStateException(what + " is managed by " + subjects + ", none of them a person");
    }

    /** Releases every adapter opened under a name this source answered whose declaration changed or went. */
    static void refreshAnswered() {
        for (String name : List.copyOf(ANSWERED)) {
            try {
                Storages.refreshDeclared(name);
            } catch (RuntimeException unreadable) {
                // A save of a storage or a host never fails over another storage's release.
                Blast.log("STORAGE: could not refresh the declared storage", name, ":", unreadable.getMessage());
            }
        }
    }

    /** A folder target's settings, {@code path}, as the local folder kind's {@code folder}. */
    private static @NonNull Map<String, Object> folderOfPath(@NonNull Map<String, Object> settings) {
        if (settings.containsKey(LocalFolderStorageKind.FOLDER.getName()) || !settings.containsKey("path")) {
            return settings;
        }
        Map<String, Object> upgraded = new LinkedHashMap<>(settings);
        // The folder target read a relative path against the working directory; the folder kind reads one against
        // the settings root, so the path is made absolute here as the target made it.
        upgraded.put(LocalFolderStorageKind.FOLDER.getName(),
            Path.of(trimmed(upgraded.remove("path"))).toAbsolutePath().normalize().toString());
        return upgraded;
    }

    /**
     * An SSH target's settings, {@code server} and {@code path}, as the SFTP kind's: the same machine, account, client
     * key, pinned host key and base path ssh used.
     *
     * @throws be.elevenways.zenit.common.validation.Violations when the host is gone, has no ssh lane, is
     *         unconfirmed or is quarantined (HostAdmission)
     * @throws IllegalArgumentException when the settings name no host or no absolute path
     */
    private static @NonNull Map<String, Object> sftpOfHostRecord(@NonNull Map<String, Object> settings) {
        if (!settings.containsKey(SSH_HOST_RECORD)) {
            return settings;
        }
        String path = trimmed(settings.get("path"));
        String spelling = trimmed(settings.get(SSH_HOST_RECORD));
        if (spelling.isEmpty() || !path.startsWith("/")) {
            throw new IllegalArgumentException("An SSH backup target needs a host and an absolute remote directory");
        }
        Row server = Models.get(HostModel.class).findById(HostModel.canonicalHostId(spelling));
        if (server == null) {
            throw new IllegalArgumentException("The SSH backup target's host '" + spelling + "' is gone");
        }
        HostAdmission.requireBackupDestination(server);
        HostKeys.Target target = HostKeys.parseTarget(server.get(HostModel.SSH_TARGET));
        int at = target.destination().lastIndexOf('@');
        Map<String, Object> upgraded = new LinkedHashMap<>();
        upgraded.put(SftpStorageKind.HOST.getName(), target.host());
        upgraded.put(SftpStorageKind.PORT.getName(), target.port() > 0 ? target.port() : 22);
        upgraded.put(SftpStorageKind.FOLDER.getName(), baseOf(path));
        // ssh signed in as this process's own user when the host record names none.
        upgraded.put(SftpStorageKind.USER.getName(), at > 0 ? target.destination().substring(0, at)
            : System.getProperty("user.name"));
        upgraded.put(SftpStorageKind.SIGN_IN.getName(), SftpStorageKind.SIGN_IN_KEY);
        upgraded.put(SftpStorageKind.PRIVATE_KEY.getName(), server.get(HostTrustSlot.SSH.clientPrivate()));
        upgraded.put(SftpStorageKind.HOST_KEY.getName(),
            HostKeys.fingerprintOf(String.valueOf((Object) server.get(HostTrustSlot.SSH.material()))));
        return upgraded;
    }

    /** @return the base path without trailing slashes ("/" stays "/"), as the SSH target joined its keys */
    static @NonNull String baseOf(@NonNull String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
