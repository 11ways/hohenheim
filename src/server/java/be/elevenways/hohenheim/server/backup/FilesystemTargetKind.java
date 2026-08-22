package be.elevenways.hohenheim.server.backup;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Filesystem backup-target kind: a directory path. The FLOOR implementation -- a
 * local directory on a combined control+compute host is NOT off-host, and the help
 * text tells the operator so; point it at a mounted remote filesystem or a directory
 * that is itself replicated off-host.
 */
public final class FilesystemTargetKind implements BackupTargetKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "filesystem");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField PATH = SETTINGS_SCHEMA.addField(
        StringField.builder().name("path")
            .label(HohenheimFormCopy.label("directory"))
            .help(HohenheimFormCopy.help("backup_target_path"))
            .build());

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Filesystem directory"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("filesystem").withFilter("scope", "backup_target_kind");
    }

    @Override
    public @NonNull Microcopy getDescription() {
        return Microcopy.of("filesystem").withFilter("scope", "backup_target_kind_description");
    }

    @Override
    public Icon getIcon() { return Icon.of("folder"); }

    @Override
    public String getColor() { return "orange"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull BackupTarget targetFor(@NonNull Map<String, Object> settings) throws IOException {
        Object path = settings.get("path");
        if (!(path instanceof String text) || text.isBlank()) {
            throw new IOException("Filesystem backup target has no directory path configured");
        }
        return new FilesystemBackupTarget(Path.of(text.trim()));
    }
}
