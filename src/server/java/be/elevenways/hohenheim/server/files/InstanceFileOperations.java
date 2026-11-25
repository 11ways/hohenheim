package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * The file manager's verbs on an app's volumes as operations, so an assistant reads and changes files through the
 * same {@link InstanceFiles} checks and {@link FileVerbs} activity the Files tab and the automation API use.
 *
 * AIDEV-NOTE: no writer of their own: each handler is one call of {@link InstanceFiles} or {@link FileVerbs}, which
 * ask files.read or files.write on the app again and keep the containment and managed-file rules. They record no
 * sentence of their own (no happened): FileVerbs records the FILES_* activity, as for every lane.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceFileOperations {

    private static final OperationCommand COMMAND = OperationCommand.perSubject(InstanceOperations.KEYS)
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    /** The file or folder a read reads; left out, the app's first volume. */
    public static final StringField READ_PATH = StringField.builder("path")
        .label(HohenheimMicrocopy.HOHENHEIM.of("app_file_path"))
        .help(HohenheimMicrocopy.HOHENHEIM.of("app_file_read_path_help"))
        .build();

    /** The file or folder a write, a delete or a move acts on. */
    public static final StringField PATH = StringField.builder("path")
        .label(HohenheimMicrocopy.HOHENHEIM.of("app_file_path"))
        .help(HohenheimMicrocopy.HOHENHEIM.of("app_file_path_help"))
        .required()
        .build();

    /** A written file's new text. */
    public static final TextField CONTENT = TextField.builder("content")
        .label(HohenheimMicrocopy.HOHENHEIM.of("app_file_content"))
        .help(HohenheimMicrocopy.HOHENHEIM.of("app_file_content_help"))
        .lines()
        .build();

    /** Where a move puts the file or folder. */
    public static final StringField TO = StringField.builder("to")
        .label(HohenheimMicrocopy.HOHENHEIM.of("app_file_to"))
        .help(HohenheimMicrocopy.HOHENHEIM.of("app_file_to_help"))
        .required()
        .build();

    /** @param path the file or folder, null for the app's first volume */
    public record PathInput(@Nullable String path) {
    }

    /**
     * @param path    the file
     * @param content its new text, null for empty
     */
    public record WriteInput(@Nullable String path, @Nullable String content) {
    }

    /**
     * @param path the file or folder moved
     * @param to   where it goes
     */
    public record MoveInput(@Nullable String path, @Nullable String to) {
    }

    /**
     * What a read found: a folder's entries, or a file's text (null for a binary file, whose size alone is said).
     *
     * @param roots every volume mount point of the app
     */
    public record Read(@NonNull String path, @Nullable List<InstanceFiles.Entry> entries, @Nullable String text,
                       long size, @NonNull List<String> roots) {
    }

    private static final OperationInput<PathInput> READ_INPUT = OperationInput.of(
        FormSpec.builder().add(READ_PATH).build(), PathInput.class, values -> new PathInput(values.get(READ_PATH)));

    private static final OperationInput<PathInput> PATH_INPUT = OperationInput.of(
        FormSpec.builder().add(PATH).build(), PathInput.class, values -> new PathInput(values.get(PATH)));

    /** Reads a file or lists a folder of an app's volumes. */
    public static final Operation<Row, PathInput, Read> READ = Operation.declare(HohenheimIds.id("read_app_file"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("app_file_read"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("app_file_read_description"))
        .happened(OperationSentences.of("read_app_file"))
        .icon(Icon.of("folder-open"))
        .one(InstanceOperations.INSTANCE)
        .gate(InstanceOperations.gate(HohenheimCapabilities.FILES_READ))
        .input(READ_INPUT)
        .result(Read.class)
        .facts(OperationFact.READ_ONLY, OperationFact.REACHES_OUTSIDE)
        .register();

    /** Creates or replaces a file of an app's volumes with the given text. */
    public static final Operation<Row, WriteInput, String> WRITE = Operation.declare(HohenheimIds.id("write_app_file"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("app_file_write"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("app_file_write_description"))
        .happened(OperationSentences.of("write_app_file"))
        .icon(Icon.of("pen-to-square"))
        .one(InstanceOperations.INSTANCE)
        .gate(InstanceOperations.gate(HohenheimCapabilities.FILES_WRITE))
        .input(OperationInput.of(FormSpec.builder().add(PATH).add(CONTENT).build(), WriteInput.class,
            values -> new WriteInput(values.get(PATH), values.get(CONTENT))))
        .result(String.class)
        .facts(OperationFact.IDEMPOTENT, OperationFact.REACHES_OUTSIDE)
        .command(COMMAND)
        .register();

    /** Makes a folder in an app's volumes. */
    public static final Operation<Row, PathInput, String> MAKE_FOLDER =
        Operation.declare(HohenheimIds.id("make_app_folder"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("app_folder_make"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("app_folder_make_description"))
            .happened(OperationSentences.of("make_app_folder"))
            .icon(Icon.of("folder-plus"))
            .one(InstanceOperations.INSTANCE)
            .gate(InstanceOperations.gate(HohenheimCapabilities.FILES_WRITE))
            .input(PATH_INPUT)
            .result(String.class)
            .facts(OperationFact.IDEMPOTENT, OperationFact.REACHES_OUTSIDE)
            .command(COMMAND)
            .register();

    /** Deletes a file, a link or a folder tree of an app's volumes. */
    public static final Operation<Row, PathInput, String> DELETE =
        Operation.declare(HohenheimIds.id("delete_app_file"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("app_file_delete"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("app_file_delete_description"))
            .happened(OperationSentences.of("delete_app_file"))
            .icon(Icon.TRASH)
            .one(InstanceOperations.INSTANCE)
            .gate(InstanceOperations.gate(HohenheimCapabilities.FILES_WRITE))
            .input(PATH_INPUT)
            .result(String.class)
            .facts(OperationFact.DESTRUCTIVE, OperationFact.IDEMPOTENT, OperationFact.REACHES_OUTSIDE)
            .command(COMMAND)
            .register();

    /** Renames or moves a file or folder inside an app's volumes. */
    public static final Operation<Row, MoveInput, String> MOVE = Operation.declare(HohenheimIds.id("move_app_file"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("app_file_move"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("app_file_move_description"))
        .happened(OperationSentences.of("move_app_file"))
        .icon(Icon.of("arrow-right-arrow-left"))
        .one(InstanceOperations.INSTANCE)
        .gate(InstanceOperations.gate(HohenheimCapabilities.FILES_WRITE))
        .input(OperationInput.of(FormSpec.builder().add(PATH).add(TO).build(), MoveInput.class,
            values -> new MoveInput(values.get(PATH), values.get(TO))))
        .result(String.class)
        .facts(OperationFact.IDEMPOTENT, OperationFact.REACHES_OUTSIDE)
        .command(COMMAND)
        .register();

    static {
        OperationHandlers.attach(READ).handle(call -> read(appId(call.subject()),
            Objects.requireNonNull(call.input()).path()));
        OperationHandlers.attach(WRITE).handle(call -> {
            WriteInput input = Objects.requireNonNull(call.input());
            String path = Objects.requireNonNull(input.path());
            new FileVerbs().write(appId(call.subject()), path,
                Objects.requireNonNullElse(input.content(), "").getBytes(StandardCharsets.UTF_8));
            return path;
        });
        OperationHandlers.attach(MAKE_FOLDER).handle(call -> {
            String path = Objects.requireNonNull(Objects.requireNonNull(call.input()).path());
            new FileVerbs().makeDirectory(appId(call.subject()), path);
            return path;
        });
        OperationHandlers.attach(DELETE).handle(call -> {
            String path = Objects.requireNonNull(Objects.requireNonNull(call.input()).path());
            new FileVerbs().delete(appId(call.subject()), path);
            return path;
        });
        OperationHandlers.attach(MOVE).handle(call -> {
            MoveInput input = Objects.requireNonNull(call.input());
            String from = Objects.requireNonNull(input.path());
            String to = Objects.requireNonNull(input.to());
            new FileVerbs().rename(appId(call.subject()), from, to);
            return from + " -> " + to;
        });
    }

    private InstanceFileOperations() {
    }

    /** Installs the handlers; idempotent through the class initializer. */
    public static void init() {
    }

    private static int appId(@NonNull Row app) {
        return Objects.requireNonNull(app.get(InstanceModel.ID));
    }

    /** @return the folder's listing, or the file's text, or for a binary file its size alone */
    private static @NonNull Read read(int appId, @Nullable String path) {
        InstanceFiles files = new InstanceFiles();
        InstanceFiles.Entry leaf = path == null || path.isEmpty() ? null : files.stat(appId, path);
        if (path != null && !path.isEmpty() && leaf == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_not_found"));
        }
        if (leaf == null || "DIRECTORY".equals(leaf.kind())) {
            InstanceFiles.Listing listing = files.list(appId, path);
            return new Read(listing.path(), listing.entries(), null, 0, listing.volumeRoots());
        }
        byte[] content = files.read(appId, path);
        return new Read(path, null, textOf(content), content.length, files.volumeRoots(appId));
    }

    /** @return the bytes as UTF-8 text, null for bytes that are no text (a NUL or an invalid sequence) */
    private static @Nullable String textOf(byte @NonNull [] content) {
        for (byte b : content) {
            if (b == 0) return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException binary) {
            return null;
        }
    }
}
