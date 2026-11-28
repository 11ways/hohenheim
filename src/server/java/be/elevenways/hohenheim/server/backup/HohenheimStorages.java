package be.elevenways.hohenheim.server.backup;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
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
import be.elevenways.zenit.auth.cms.SubjectTag;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.PrincipalKind;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.storage.StorageKindRegistry;
import be.elevenways.zenit.common.validation.Violations;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * Hohenheim's source of the storages people declare: its {@link BackupTargetModel} rows, handed to the storage layer
 * through {@link DeclaredStorages#SEAM} and opened there through their kind.
 *
 * AIDEV-NOTE: a row written before storages existed keeps its settings. Its kind resolves through the storage kind
 * registry's own former spellings ({@link StorageKindRegistry#STORED_NAMES}: {@code hohenheim:filesystem} is the local
 * folder kind, {@code hohenheim:ssh} the SFTP kind), never a repo-wide stored-id chain, since {@code hohenheim:ssh} is
 * also a live git connection kind; the boot's stored-id reconciler rewrites the kind column to today's spelling. A
 * former SHAPE (a folder target's {@code path}) is read in the kind's shape as the row is found, so every reader
 * (resolve, form, page, list, tool) reads one value and the row's next save writes that shape. An SSH target's
 * {@code server} and {@code path} are no shape but a LINK: the host record gives the address, the account, the client
 * key and the pinned host key, re-read and re-gated (HostAdmission) at every resolve, so the host record stays the one
 * trust authority and a rotated key or a quarantine reaches the storage at once; a reader sees where it goes
 * ({@link #readableSettings}, never the client key), and a save keeps the link unless it changes where the row goes,
 * which is refused ({@link #keepHostRecordLink}). The base path is unchanged, so every stored key still resolves.
 *
 * AIDEV-NOTE: who owns a storage is who holds its manage grant (no owner column, the ProjectModel rule); none is the
 * operator. Only an operator's storage may reach a private address, and only while BOTH its own switch
 * ({@link BackupTargetModel#PRIVATE_ADDRESSES_ALLOWED}, an administrator's) and this host's boot
 * ({@code storage.allow_private_networks}) allow it; M009_StorageChoices turned the switch on for every storage older
 * than it, so each keeps the reach it had. An SSH-era row is the one exception: its address is the host record's,
 * which HostAdmission admitted as a backup destination (checked again on every read), so that admission decides its
 * reach as it decided where ssh went ({@link StorageReach#ADMITTED_HOST}), loopback and private hosts included, with
 * neither the switch nor the boot opt-in.
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

    /** The setting an SSH-era row names its remote directory by. */
    private static final String SSH_PATH = "path";

    /** The one instance, installed as the class loads. */
    public static final HohenheimStorages INSTANCE = new HohenheimStorages();

    /** The names this source answered, so a change of any row releases what was opened under its old name. */
    private static final Set<String> ANSWERED = ConcurrentHashMap.newKeySet();

    static {
        StorageKindRegistry.STORED_NAMES.renameType(STORED_FOLDER_KIND, LocalFolderStorageKind.ID);
        StorageKindRegistry.STORED_NAMES.upgradeConfig(LocalFolderStorageKind.ID, HohenheimStorages::folderOfPath);
        StorageKindRegistry.STORED_NAMES.renameType(STORED_SSH_KIND, SftpStorageKind.ID);
        StorageKindRegistry.STORED_NAMES.upgradeConfig(SftpStorageKind.ID, HohenheimStorages::sftpOfHostRecord);
        DeclaredStorages.SEAM.install(INSTANCE);
        BackupTargetModel.SCHEMA.addAfterFindHook(found -> found.getRows().forEach(HohenheimStorages::readFormerShape));
        BackupTargetModel.SCHEMA.addAfterSaveHook(context -> refreshAnswered());
        BackupTargetModel.SCHEMA.addAfterRemoveHook(context -> refreshAnswered());
        // A host record an SSH-era storage reaches through decides that storage's settings and its gate: a rotated key,
        // a new pin or a quarantine releases the adapter opened before, so an exchange in flight fails at once.
        HostModel.SCHEMA.addAfterSaveHook(context -> refreshAnswered());
    }

    private HohenheimStorages() {}

    /**
     * Reads a found row's settings stored in a former kind's shape in its kind's shape; an SSH-era link stays as stored
     * (its translation reads another record and asks its admission, which a find never does).
     */
    private static void readFormerShape(@NonNull Row row) {
        if (!row.has(BackupTargetModel.SETTINGS) || !row.has(BackupTargetModel.KIND)) {
            return;
        }
        Map<String, Object> stored = RawValues.map(row.get(BackupTargetModel.SETTINGS));
        Identifier kind;
        try {
            kind = kindOf(row);
        } catch (IllegalArgumentException unreadable) {
            return;
        }
        if (isHostRecordLink(kind, stored)) {
            return;
        }
        Map<String, Object> current = StorageKindRegistry.settingsOf(kind, stored);
        if (!current.equals(stored)) {
            row.set(BackupTargetModel.SETTINGS, current);
        }
    }

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
     * @return the settings every reader of the row shows (its form, the read tool) in its kind's shape: a former kind's
     *         settings translated as its kind reads them, and for an SSH-era row where its host record sends it (host,
     *         port, folder, login, pinned key), never the host record's client key and asking no admission
     */
    public static @NonNull Map<String, Object> readableSettings(@NonNull Row row) {
        Map<String, Object> stored = RawValues.map(row.get(BackupTargetModel.SETTINGS));
        Identifier kind;
        try {
            kind = kindOf(row);
        } catch (IllegalArgumentException unreadable) {
            return stored;
        }
        if (isHostRecordLink(kind, stored)) {
            try {
                return hostRecordSettings(stored, false);
            } catch (IllegalArgumentException gone) {
                // A host record that is gone or unreadable sends the row nowhere: its stored link is all there is.
                return stored;
            }
        }
        return StorageKindRegistry.settingsOf(kind, stored);
    }

    /** @return the name of the host record an SSH-era row follows, null for any other row or a host that is gone */
    public static @Nullable String hostRecordOf(@NonNull Row row) {
        Map<String, Object> stored = RawValues.map(row.get(BackupTargetModel.SETTINGS));
        try {
            if (!isHostRecordLink(kindOf(row), stored)) {
                return null;
            }
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
        Row server = Models.get(HostModel.class)
            .findById(HostModel.canonicalHostId(trimmed(stored.get(SSH_HOST_RECORD))));
        return server == null ? null : trimmed(server.get(HostModel.NAME));
    }

    /**
     * Keeps an SSH-era row's host record link through a save that changes nothing the link decides (a rename, its
     * form saved as it was drawn): the stored link is written back, never the settings the form showed.
     *
     * @throws Violations {@code backup_target_follows_host} when the save changes where the row goes or gives it a
     *                    credential of its own: that is the host record's to change, or a new storage's
     */
    public static void keepHostRecordLink(@NonNull Row row) {
        Object id = row.get(BackupTargetModel.ID);
        Row stored = id == null ? null : Models.get(BackupTargetModel.class).findById(id);
        if (stored == null) {
            return;
        }
        Map<String, Object> link = RawValues.map(stored.get(BackupTargetModel.SETTINGS));
        Identifier kind = kindOf(stored);
        if (!isHostRecordLink(kind, link) || !kind.equals(kindOf(row))) {
            // Another kind chosen is a new declaration a person made on purpose, judged as any other.
            return;
        }
        Map<String, Object> saved = RawValues.map(row.get(BackupTargetModel.SETTINGS));
        Map<String, Object> shown = readableSettings(stored);
        for (Map.Entry<String, Object> entry : saved.entrySet()) {
            String name = entry.getKey();
            if (name.equals(SSH_HOST_RECORD) || name.equals(SSH_PATH)) {
                // The link itself: a save may carry it as stored, never move it to another host or folder.
                if (!sameSetting(entry.getValue(), link.get(name))) {
                    throw followsHost(stored, link, name);
                }
                continue;
            }
            Field<?, ?> field = SftpStorageKind.SETTINGS.getField(name);
            Object expected = shown.containsKey(name) ? shown.get(name)
                : field == null ? null : field.getDefaultValue();
            if (!sameSetting(entry.getValue(), expected)) {
                throw followsHost(stored, link, name);
            }
        }
        row.set(BackupTargetModel.SETTINGS, link);
    }

    /** @return the refusal of a save changing where an SSH-era row goes, at the setting it changed */
    private static @NonNull Violations followsHost(@NonNull Row stored, @NonNull Map<String, Object> link,
                                                   @NonNull String setting) {
        return Violations.ofField(BackupTargetModel.SETTINGS.getName() + "." + setting, null,
            HohenheimMicrocopy.VIOLATIONS.of("backup_target_follows_host")
                .withArg("name", trimmed(stored.get(BackupTargetModel.NAME)))
                .withArg("host", Objects.requireNonNullElse(hostRecordOf(stored), trimmed(link.get(SSH_HOST_RECORD)))));
    }

    /** @return whether a saved setting says what the shown one does: blank is unset, a number its digits */
    private static boolean sameSetting(@Nullable Object saved, @Nullable Object shown) {
        String one = saved instanceof Secret secret ? secret.reveal() : saved == null ? "" : String.valueOf(saved);
        String other = shown == null ? "" : String.valueOf(shown);
        return one.strip().equals(other.strip());
    }

    /** @return whether the settings are an SSH-era row's link to a host record */
    private static boolean isHostRecordLink(@NonNull Identifier kind, @NonNull Map<String, Object> stored) {
        return SftpStorageKind.ID.equals(kind) && stored.containsKey(SSH_HOST_RECORD);
    }

    /**
     * @return the declaration a row holds once saved by {@code owner}: a row not yet created has no grants to read
     * @throws IllegalArgumentException when SFTP settings name a host record outside an operator's SSH-era row: the
     *                                  host record's client key is the operator's alone
     */
    public static @NonNull DeclaredStorage declarationOf(@NonNull Row row, @NonNull StorageOwner owner) {
        Identifier kind = kindOf(row);
        Map<String, Object> stored = RawValues.map(row.get(BackupTargetModel.SETTINGS));
        boolean hostRecord = isHostRecordLink(kind, stored);
        if (hostRecord && !(owner instanceof StorageOwner.Operator)) {
            // The upgrade below would hand these settings the host record's client key and pin: only the operator's
            // row stored as an SSH target before storages existed names one. Its kind spelling is no mark (the boot's
            // reconciler rewrites it); the settings are: a kind's form and tool are closed-world, so no write since
            // names a host record.
            throw new IllegalArgumentException("Only an operator's storage stored as an SSH backup target names a "
                + "host record");
        }
        Map<String, Object> settings = StorageKindRegistry.settingsOf(kind, stored);
        Object id = row.get(BackupTargetModel.ID);
        // An SSH-era row reaches what its host record's admission admitted (HostAdmission, just checked by the
        // upgrade); every other operator storage private networks when its switch is on (and the boot opts in, which
        // the storage layer asks), else the internet; a tenant's the internet, whatever its row holds.
        StorageReach reach = !(owner instanceof StorageOwner.Operator) ? StorageReach.PUBLIC_INTERNET
            : hostRecord ? StorageReach.ADMITTED_HOST
            : Boolean.TRUE.equals(row.get(BackupTargetModel.PRIVATE_ADDRESSES_ALLOWED)) ? StorageReach.PRIVATE_NETWORKS
            : StorageReach.PUBLIC_INTERNET;
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

    /** @return the kind the row names, a former spelling read through the storage kind registry */
    public static @NonNull Identifier kindOf(@NonNull Row row) {
        return StorageKindRegistry.current(trimmed(row.get(BackupTargetModel.KIND)));
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
        return hostRecordSettings(settings, true);
    }

    /**
     * @param resolving true to open the storage: the host's admission as a backup destination is asked and its client
     *                  key handed over; false for a reader, who sees where the row goes and never that key
     * @return the SFTP settings the host record gives an SSH target's {@code server} and {@code path}
     * @throws IllegalArgumentException when the settings name no host or no absolute path, or the host is gone
     */
    private static @NonNull Map<String, Object> hostRecordSettings(@NonNull Map<String, Object> settings,
                                                                   boolean resolving) {
        String path = trimmed(settings.get(SSH_PATH));
        String spelling = trimmed(settings.get(SSH_HOST_RECORD));
        if (spelling.isEmpty() || !path.startsWith("/")) {
            throw new IllegalArgumentException("An SSH backup target needs a host and an absolute remote directory");
        }
        Row server = Models.get(HostModel.class).findById(HostModel.canonicalHostId(spelling));
        if (server == null) {
            throw new IllegalArgumentException("The SSH backup target's host '" + spelling + "' is gone");
        }
        if (resolving) {
            HostAdmission.requireBackupDestination(server);
        }
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
        if (resolving) {
            upgraded.put(SftpStorageKind.PRIVATE_KEY.getName(), server.get(HostTrustSlot.SSH.clientPrivate()));
        }
        String material = server.get(HostTrustSlot.SSH.material());
        if (material != null && !material.isBlank()) {
            // The pin with its key type, as OpenSSH asked the server for the pinned key's type: a server offering
            // another type first still reaches the key the host record pins.
            String keyLine = HostKeys.keyLineOf(material);
            upgraded.put(SftpStorageKind.HOST_KEY.getName(), keyLine.substring(0, keyLine.indexOf(' ')) + " "
                + HostKeys.fingerprintOf(keyLine));
        }
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
