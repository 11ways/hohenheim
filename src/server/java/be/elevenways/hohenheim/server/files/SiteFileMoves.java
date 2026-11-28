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
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.storage.PutOptions;
import be.elevenways.zenit.server.storage.StorageAdapter;
import be.elevenways.zenit.server.storage.StoredObject;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Changing where a site keeps its own files ("Files kept in") while it has some: THE move, a background run that
 * copies every file to the new storage, verifies each copy by the SHA-256 the new storage computes, switches the site
 * over and removes the old copies last, the site serving its files throughout.
 *
 * AIDEV-NOTE: the site reads the storage its settings name on every request, so before the switch the old storage
 * holds every file and after it the new one does; a file written to the old storage during the copy is copied by the
 * second pass just before the switch. A save that changes the storage while the old one holds a file the new one
 * lacks is refused ({@code site_files_move_first}): the change is offered only with the move until Jelle rules on
 * leaving files where they are (H1 brief item 6).
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

    static {
        OperationHandlers.attach(MOVE)
            .applies(SiteFiles::servesOwnFiles)
            .authorize((site, input, access) -> input == null || input.storage() == null
                || SitePicks.mayUse(input.storage(), access) ? null
                : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "the storage is not one the caller may use"))
            .handle(call -> move(call.subject(), Objects.requireNonNull(Objects.requireNonNull(call.input(),
                "a move names its storage").storage(), "a move names its storage"), call.progress(),
                call::reportStepDone));
        SiteModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null || context.isCreate() || !row.has(SiteModel.SETTINGS.getName())) {
                return;
            }
            Row stored = StoredRows.of(Models.get(SiteModel.class), row);
            if (stored == null || !SiteFiles.servesOwnFiles(stored)) {
                return;
            }
            Map<String, Object> before = RawValues.map(stored.get(SiteModel.SETTINGS));
            Map<String, Object> after = RawValues.map(row.get(SiteModel.SETTINGS));
            String from = SiteFiles.storageNameOf(before);
            String to = SiteFiles.storageNameOf(after);
            if (!from.equals(to)) {
                requireHeldBy(stored.get(SiteModel.ID), before, after, to);
            }
        });
    }

    private SiteFileMoves() {
    }

    /**
     * Refuses a change of storage that would leave a file behind: every file the old storage holds for the site must
     * be in the new one first.
     *
     * @throws Violations {@code site_files_move_first} naming how many files the new storage lacks
     */
    static void requireHeldBy(int siteId, @NonNull Map<String, Object> before, @NonNull Map<String, Object> after,
                              @NonNull String to) {
        StorageAdapter source = SiteFiles.storageOf(before);
        StorageAdapter target = SiteFiles.storageOf(after);
        long missing = 0;
        for (String key : source.list(SiteFiles.prefixOf(siteId))) {
            if (!target.exists(key)) {
                missing++;
            }
        }
        if (missing > 0) {
            throw Violations.ofField(SiteModel.SETTINGS.getName() + "." + StaticUpstreamKind.STORAGE.getName(), to,
                HohenheimMicrocopy.VIOLATIONS.of("site_files_move_first")
                    .withArg("files", HohenheimCounts.of("files", missing)));
        }
    }

    /**
     * The move: copy and verify each file, a second pass for what was written meanwhile, the switch, then the removal
     * of the old copies.
     *
     * @param progress told the fraction of the files copied
     * @param stepDone told each step's line as it finishes: copied, switched, removed
     * @return how many files moved
     * @throws Violations {@code site_files_move_same} when the site keeps its files there already, or
     *         {@code site_files_copy_unverified} when a copy's SHA-256 differs from its source's
     */
    public static int move(@NonNull Row site, @NonNull String to, @NonNull ProgressSink progress,
                           @NonNull Consumer<Microcopy> stepDone) {
        int siteId = site.get(SiteModel.ID);
        Map<String, Object> settings = RawValues.map(site.get(SiteModel.SETTINGS));
        if (SiteFiles.storageNameOf(settings).equals(to)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("site_files_move_same").withArg("storage", to));
        }
        Map<String, Object> moved = new LinkedHashMap<>(settings);
        moved.put(StaticUpstreamKind.STORAGE.getName(), to);
        StorageAdapter source = SiteFiles.storageOf(settings);
        StorageAdapter target = SiteFiles.storageOf(moved);
        String prefix = SiteFiles.prefixOf(siteId);

        Map<String, String> copied = new LinkedHashMap<>();
        copyAll(source, target, source.list(prefix), copied, progress);
        // What a person put there, or replaced, while the first pass ran: copied again when its bytes changed.
        copyAll(source, target, source.list(prefix), copied, progress);
        stepDone.accept(HohenheimMicrocopy.SITE.of("move_files_copied")
            .withArg("files", HohenheimCounts.of("files", copied.size())));

        site.set(SiteModel.SETTINGS, moved);
        Models.get(SiteModel.class).save(site);
        SiteFiles.forget(siteId);
        stepDone.accept(HohenheimMicrocopy.SITE.of("move_files_switched").withArg("storage", to));

        // Only what was copied and verified is removed, and only while it is still the verified bytes: a file
        // written to the old storage after the second pass stays there rather than being lost.
        for (Map.Entry<String, String> verified : copied.entrySet()) {
            if (verified.getValue().equals(source.storedSha256(verified.getKey()))) {
                source.deleteIfExists(verified.getKey());
            }
        }
        if (source.list(prefix).isEmpty()) {
            source.deleteFolder(prefix);
        }
        stepDone.accept(HohenheimMicrocopy.SITE.of("move_files_removed")
            .withArg("files", HohenheimCounts.of("files", copied.size())));
        return copied.size();
    }

    /**
     * Copies each key whose bytes the new storage does not hold yet, durably, and verifies it by the SHA-256 the new
     * storage computes.
     *
     * @param copied the verified SHA-256 of each key copied so far, extended here
     */
    private static void copyAll(@NonNull StorageAdapter source, @NonNull StorageAdapter target,
                                @NonNull List<String> keys, @NonNull Map<String, String> copied,
                                @NonNull ProgressSink progress) {
        int walked = 0;
        for (String key : keys) {
            walked++;
            StoredObject object = source.stat(key);
            if (object == null) {
                // Removed since it was listed: nothing to keep.
                continue;
            }
            String held = source.storedSha256(key);
            if (held.equals(copied.get(key))) {
                continue;
            }
            try (InputStream in = source.get(key)) {
                target.put(key, in, PutOptions.builder().contentLength(object.size()).durable().build());
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
            if (!held.equals(target.storedSha256(key))) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("site_files_copy_unverified")
                    .withArg("file", key));
            }
            copied.put(key, held);
            progress.reportProgress((double) walked / keys.size());
        }
    }
}
