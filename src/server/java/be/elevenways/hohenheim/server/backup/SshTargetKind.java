package be.elevenways.hohenheim.server.backup;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.Map;

/**
 * SSH backup-target kind: a directory on ANOTHER host, reached over ssh with strict
 * host-key checking (pin the key in the settings, or pre-trust the host in the
 * controller user's known_hosts -- accept-new does not exist here by decision).
 */
public final class SshTargetKind implements BackupTargetKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "ssh");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField TARGET = SETTINGS_SCHEMA.addField(
        StringField.builder().name("target")
            .label(HohenheimFormCopy.label("ssh_target"))
            .help(HohenheimFormCopy.help("backup_ssh_target"))
            .build());

    public static final StringField PATH = SETTINGS_SCHEMA.addField(
        StringField.builder().name("path")
            .label(HohenheimFormCopy.label("directory"))
            .help(HohenheimFormCopy.help("backup_ssh_path"))
            .build());

    public static final StringField HOST_KEY = SETTINGS_SCHEMA.addField(
        StringField.builder().name("host_key")
            .label(HohenheimFormCopy.label("host_key"))
            .help(HohenheimFormCopy.help("backup_ssh_host_key"))
            .build());

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "SSH host"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("ssh").withFilter("scope", "backup_target_kind");
    }

    @Override
    public String getDescription() {
        return "A directory on another host over ssh; a different failure domain when "
            + "the host actually is one";
    }

    @Override
    public Icon getIcon() { return Icon.of("server"); }

    @Override
    public String getColor() { return "blue"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull BackupTarget targetFor(@NonNull Map<String, Object> settings) throws IOException {
        String target = text(settings.get("target"));
        String path = text(settings.get("path"));
        if (target.isEmpty()) {
            throw new IOException("SSH backup target has no user@host configured");
        }
        if (path.isEmpty() || !path.startsWith("/")) {
            throw new IOException("SSH backup target needs an absolute remote directory path");
        }
        String hostKey = text(settings.get("host_key"));
        return new SshBackupTarget(target, path, hostKey.isEmpty() ? null : hostKey);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
