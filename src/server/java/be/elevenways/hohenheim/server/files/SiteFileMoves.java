package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimCounts;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.async.ProgressSink;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationSteps;
import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.home.ReadThroughStorage;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.storage.PutOptions;
import be.elevenways.zenit.server.storage.StorageAdapter;
import be.elevenways.zenit.server.storage.StoredObject;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Changing where a site keeps its own files ("Files kept in"): THE move, a background run that copies every file to the
 * new storage, verifies each copy by the SHA-256 the new storage computes, switches the site over and removes the old
 * copies last, the site serving its files throughout; or a save of the new storage alone, which leaves the files where
 * they are and keeps them working (Jelle, 2026-11-28).
 *
 * AIDEV-NOTE: the site reads the storage its settings name on every request and every write resolves it again, so
 * before the switch the old storage holds every file and after it the new one does. A file written to the old storage
 * during the copy is copied by the second pass just before the switch; one written by a write that resolved the old
 * storage just before the switch and landed after it is caught by VERIFY-AFTER-SWITCH: after the switch every key the
 * old storage holds is settled again (copied when the new storage lacks it or holds only this move's earlier copy,
 * left alone when a write since the switch put a newer one there) right before its old copy is removed, so nothing
 * written is left only where the site no longer reads. No write is held: a hold would stall a person's upload for the
 * length of a save.
 *
 * AIDEV-NOTE: a save that changes the storage while the old one still holds files of the site records the old one in
 * the site's settings ({@link SiteFiles#EARLIER_STORAGES}, newest first), whoever saves and whatever the write
 * carried there: the site then reads through to it (SiteFiles.storageOf), so every file keeps being served, listed
 * and deleted, while writes land in the new storage; the earlier storage counts as in use (StorageUsage). Choosing an
 * earlier storage again takes it off that list. A later move consolidates: it reads every storage the site reads, the
 * newest copy of each file winning, writes them into the storage chosen and removes them from the others, which ends
 * the read-through. A site's keys are listed under its folder with the slash ({@code sites/<id>/}): the bare prefix
 * also names {@code sites/<id>0/}, another site's files.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@BlastAutoLoad
public final class SiteFileMoves {

    /** Read by the autoload index, which loads the class and so runs the registrations below at boot. */
    public static final Object LOADED = new Object();

    /** The storage the files move to: the one picker of the storages the person may use. */
    public static final SiteStorageField TO = (SiteStorageField) SiteStorageField.builder(
        StaticUpstreamKind.STORAGE.getName())
        .required()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("storage"))
        .help(HohenheimMicrocopy.SITE.of("move_files_to_help"))
        .build();

    /** @param storage the storage the files move to */
    public record MoveInput(@Nullable String storage) {
    }

    private static final OperationCommand COMMAND = OperationCommand.perSubject()
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    /** Moves the site's own files to another storage and keeps them there; answers how many files moved. */
    public static final Operation<Row, MoveInput, Integer> MOVE =
        Operation.declare(HohenheimIds.id("move_site_files"))
            .happened(OperationSentences.of("move_site_files"))
            .label(HohenheimMicrocopy.SITE.of("move_files"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("move_site_files_description"))
            .icon(Icon.of("right-left"))
            .one(SiteOperations.SITE)
            .verb(OperationVerb.UPDATE)
            .gate(OperationGate.open().subjectCapability(HohenheimCapabilities.MANAGE))
            .input(OperationInput.of(FormSpec.builder().add(SitePicks.picker(TO)).build(), MoveInput.class,
                values -> new MoveInput(values.get(TO.getName()) instanceof String named ? named : null)))
            .result(Integer.class)
            .command(COMMAND)
            .background(OperationSteps.of(
                OperationSteps.step("copy", HohenheimMicrocopy.SITE.of("move_files_copy_step")),
                OperationSteps.step("switch", HohenheimMicrocopy.SITE.of("move_files_switch_step")),
                OperationSteps.step("remove", HohenheimMicrocopy.SITE.of("move_files_remove_step"))))
            .register();

    /** The site whose move is saving its switch on this thread, which the storage-change check lets through. */
    private static final ThreadLocal<Integer> SWITCHING = new ThreadLocal<>();

    static {
        OperationHandlers.attach(MOVE)
            .applies(SiteFiles::servesOwnFiles)
            .authorize((site, input, access) -> input == null || input.storage() == null
                || SitePicks.mayUse(input.storage(), access) ? null
                : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "the storage is not one the caller may use"))
            .handle(call -> move(call.subject(), Objects.requireNonNull(Objects.requireNonNull(call.input(),
                "a move names its storage").storage(), "a move names its storage"), call.progress(),
                call::reportStepDone));
        SiteModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || context.isCreate() || !row.has(SiteModel.SETTINGS.getName())) {
                return;
            }
            Row stored = StoredRows.of(Models.get(SiteModel.class), row);
            // The move's own switch writes the list it ended; nobody else's write carries one.
            if (stored == null || Objects.equals(SWITCHING.get(), stored.get(SiteModel.ID))) {
                return;
            }
            Map<String, Object> written = RawValues.map(row.get(SiteModel.SETTINGS));
            Map<String, Object> after = new LinkedHashMap<>(written);
            List<String> earlier = earlierAfter(stored, after);
            if (earlier.isEmpty()) {
                after.remove(SiteFiles.EARLIER_STORAGES);
            } else {
                after.put(SiteFiles.EARLIER_STORAGES, earlier);
            }
            if (!after.equals(written)) {
                row.set(SiteModel.SETTINGS, after);
            }
        });
    }

    private SiteFileMoves() {
    }

    /**
     * @param stored the site as stored before the write
     * @param after  the settings the write stores
     * @return the storages the site reads the files it did not move from after the write, newest first: the stored
     *         ones, the one it leaves first while that still holds a file of the site, the one it chooses left out
     */
    static @NonNull List<String> earlierAfter(@NonNull Row stored, @NonNull Map<String, Object> after) {
        Map<String, Object> before = RawValues.map(stored.get(SiteModel.SETTINGS));
        List<String> earlier = new ArrayList<>(SiteFiles.earlierStorageNamesOf(before));
        String from = SiteFiles.storageNameOf(before);
        String to = SiteFiles.storageNameOf(after);
        earlier.remove(to);
        if (!from.equals(to) && SiteFiles.servesOwnFiles(stored) && !earlier.contains(from)
                && holdsFilesOf(from, stored.get(SiteModel.ID))) {
            earlier.addFirst(from);
        }
        return earlier;
    }

    /** @return whether the storage holds a file of the site; one it cannot be asked now is taken to hold some */
    private static boolean holdsFilesOf(@NonNull String storage, int siteId) {
        StorageAdapter adapter;
        try {
            adapter = SiteFiles.storageNamed(storage);
        } catch (Violations unknown) {
            // No storage of that name is known here: nothing could be read from it.
            return false;
        }
        try {
            return !adapter.list(SiteFiles.keysOf(siteId)).isEmpty();
        } catch (RuntimeException unreachable) {
            return true;
        }
    }

    /**
     * The move: copy and verify each file, a second pass for what was written meanwhile, the switch, then the removal
     * of the old copies. Every storage the site reads is a source, the newest copy of a file winning; the storage
     * chosen keeps a copy of its own that is newer than every other.
     *
     * @param progress told the fraction of the files copied
     * @param stepDone told each step's line as it finishes: copied, switched, removed
     * @return how many files were copied
     * @throws Violations {@code site_files_move_same} when the site reads its files from that storage alone already, or
     *         {@code site_files_copy_unverified} when a copy's SHA-256 differs from its source's
     */
    public static int move(@NonNull Row site, @NonNull String to, @NonNull ProgressSink progress,
                           @NonNull Consumer<Microcopy> stepDone) {
        int siteId = site.get(SiteModel.ID);
        Map<String, Object> settings = RawValues.map(site.get(SiteModel.SETTINGS));
        List<String> reads = SiteFiles.readStorageNamesOf(settings);
        if (reads.equals(List.of(to))) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("site_files_move_same").withArg("storage", to));
        }
        Map<String, Object> moved = new LinkedHashMap<>(settings);
        moved.put(StaticUpstreamKind.STORAGE.getName(), to);
        moved.remove(SiteFiles.EARLIER_STORAGES);
        List<StorageAdapter> others = new ArrayList<>();
        List<StorageAdapter> newer = new ArrayList<>();
        boolean past = false;
        for (String name : reads) {
            if (name.equals(to)) {
                past = true;
                continue;
            }
            StorageAdapter read = SiteFiles.storageNamed(name);
            others.add(read);
            if (!past) {
                newer.add(read);
            }
        }
        StorageAdapter source = others.size() == 1 ? others.getFirst()
            : ReadThroughStorage.over(others.getFirst(), others.subList(1, others.size()));
        StorageAdapter target = SiteFiles.storageNamed(to);
        // A copy the chosen storage holds already wins when no storage read before it holds the file.
        boolean targetRead = past;
        Predicate<String> targetWins = key -> targetRead && target.exists(key)
            && newer.stream().noneMatch(read -> read.exists(key));
        String keys = SiteFiles.keysOf(siteId);

        Map<String, String> copied = new LinkedHashMap<>();
        copyAll(source, target, source.list(keys), copied, progress, targetWins);
        // What a person put there, or replaced, while the first pass ran: copied again when its bytes changed.
        copyAll(source, target, source.list(keys), copied, progress, targetWins);
        stepDone.accept(HohenheimMicrocopy.SITE.of("move_files_copied")
            .withArg("files", HohenheimCounts.of("files", copied.size())));

        site.set(SiteModel.SETTINGS, moved);
        SWITCHING.set(siteId);
        try {
            Models.get(SiteModel.class).save(site);
        } finally {
            SWITCHING.remove();
        }
        SiteFiles.forget(siteId);
        stepDone.accept(HohenheimMicrocopy.SITE.of("move_files_switched").withArg("storage", to));

        // Verify after the switch: each key the old storages still hold is settled once more right before its old
        // copies go, so a write that landed there after the second pass is in the new storage first.
        for (String key : source.list(keys)) {
            String settled = settled(source, target, key, copied);
            // Removed only while it still holds the bytes just settled: a write landing between the two is kept for
            // the next look rather than lost.
            if (settled != null && source.stat(key) != null && settled.equals(source.storedSha256(key))) {
                source.deleteIfExists(key);
            }
        }
        if (source.list(keys).isEmpty()) {
            source.deleteFolder(SiteFiles.prefixOf(siteId));
        }
        stepDone.accept(HohenheimMicrocopy.SITE.of("move_files_removed")
            .withArg("files", HohenheimCounts.of("files", copied.size())));
        return copied.size();
    }

    /**
     * Settles one key after the switch: the new storage holds it as the old one does, or holds a newer copy a write
     * since the switch put there.
     *
     * @param copied the verified SHA-256 of each key this move copied, extended here
     * @return the SHA-256 of the old copy that is settled, so it may go; null for a key no longer in the old storage
     */
    private static @Nullable String settled(@NonNull StorageAdapter source, @NonNull StorageAdapter target,
                                            @NonNull String key, @NonNull Map<String, String> copied) {
        StoredObject object = source.stat(key);
        if (object == null) {
            return null;
        }
        String held = source.storedSha256(key);
        String ours = copied.get(key);
        if (held.equals(ours)) {
            return held;
        }
        String there = target.stat(key) == null ? null : target.storedSha256(key);
        if (there != null && !there.equals(ours)) {
            // Written to the new storage since the switch: that copy is the newer one, and the site serves it.
            return held;
        }
        if (there == null && ours != null) {
            // This move put it there and the site removed it since the switch: never brought back.
            return held;
        }
        copyOne(source, target, key, object, held);
        copied.put(key, held);
        return held;
    }

    /**
     * Copies each key whose bytes the new storage does not hold yet, durably, and verifies it by the SHA-256 the new
     * storage computes; a key whose copy in the new storage wins is left as it is there.
     *
     * @param copied     the verified SHA-256 of each key copied so far, extended here
     * @param targetWins whether the new storage's own copy of a key is the newest the site reads
     */
    private static void copyAll(@NonNull StorageAdapter source, @NonNull StorageAdapter target,
                                @NonNull List<String> keys, @NonNull Map<String, String> copied,
                                @NonNull ProgressSink progress, @NonNull Predicate<String> targetWins) {
        int walked = 0;
        for (String key : keys) {
            walked++;
            StoredObject object = source.stat(key);
            if (object == null || targetWins.test(key)) {
                // Removed since it was listed, or newest where it goes: nothing to copy.
                continue;
            }
            String held = source.storedSha256(key);
            if (held.equals(copied.get(key))) {
                continue;
            }
            copyOne(source, target, key, object, held);
            copied.put(key, held);
            progress.reportProgress((double) walked / keys.size());
        }
    }

    /**
     * Copies one key durably and verifies it by the SHA-256 the new storage computes.
     *
     * @throws Violations {@code site_files_copy_unverified} when the copy's SHA-256 differs from its source's
     */
    private static void copyOne(@NonNull StorageAdapter source, @NonNull StorageAdapter target, @NonNull String key,
                                @NonNull StoredObject object, @NonNull String held) {
        try (InputStream in = source.get(key)) {
            target.put(key, in, PutOptions.builder().contentLength(object.size()).durable().build());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        if (!held.equals(target.storedSha256(key))) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("site_files_copy_unverified")
                .withArg("file", key));
        }
    }
}
