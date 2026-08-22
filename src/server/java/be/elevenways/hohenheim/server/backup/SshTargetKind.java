package be.elevenways.hohenheim.server.backup;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.RegistryEnumField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.Map;

/**
 * SSH backup-target kind: a directory on an ENROLLED HOST, reached over ssh through
 * that host record's pinned, operator-confirmed identity.
 *
 * The kind stores a host REFERENCE and a path -- no {@code user@host}, no pasted key
 * line. The trust ceremony (scan, show the fingerprint, confirm out of band, pin,
 * quarantine on mismatch) already exists on the host record and is the only authority
 * over the destination's identity; accept-new and ambient known_hosts do not exist
 * anywhere in this lane.
 */
public final class SshTargetKind implements BackupTargetKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "ssh");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /**
     * The destination host record, stored as the {@code hohenheim:<id>} registry key
     * every other host-picking settings field uses, so a host rename never re-points a
     * backup target at a different machine.
     */
    public static final EnumField SERVER = SETTINGS_SCHEMA.addField(
        RegistryEnumField.builder("server")
            .registry(ServerOptions.REGISTRY)
            .label(HohenheimFormCopy.label("server"))
            .help(HohenheimFormCopy.help("backup_ssh_server"))
            .build());

    public static final StringField PATH = SETTINGS_SCHEMA.addField(
        StringField.builder().name("path")
            .label(HohenheimFormCopy.label("directory"))
            .help(HohenheimFormCopy.help("backup_ssh_path"))
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
    public @NonNull Microcopy getDescription() {
        return Microcopy.of("ssh").withFilter("scope", "backup_target_kind_description");
    }

    @Override
    public Icon getIcon() { return Icon.of("server"); }

    @Override
    public String getColor() { return "blue"; }

    @Override
    public Schema getSchema() {
        ServerOptions.ensureFresh();
        return SETTINGS_SCHEMA;
    }

    /**
     * @throws IOException when the settings name no host or no absolute path
     * @throws Violations when the named host is not an ssh host, is unconfirmed or is
     *         quarantined
     */
    @Override
    public @NonNull BackupTarget targetFor(@NonNull Map<String, Object> settings) throws IOException {
        String path = text(settings.get("path"));
        if (path.isEmpty() || !path.startsWith("/")) {
            throw new IOException("SSH backup target needs an absolute remote directory path");
        }
        String spelling = text(settings.get("server"));
        if (spelling.isEmpty()) {
            // Explicit, because canonicalServerId folds a blank spelling to the LOCAL
            // host, and "the local host is not an ssh destination" is a confusing way to
            // say "you picked no host".
            throw new IOException("SSH backup target names no destination host");
        }
        int serverId;
        try {
            serverId = ServerModel.canonicalServerId(spelling);
        } catch (IllegalArgumentException unknown) {
            throw new IOException("SSH backup target names no enrolled host: "
                + unknown.getMessage());
        }
        SshBackupTarget target = new SshBackupTarget(serverId, path);
        // Gate at RESOLUTION as well as per exchange: the admin's test-connection action
        // and every backup operation resolve through here, so an unusable destination is
        // named before any downtime is spent on it.
        target.requireUsableDestination();
        return target;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
