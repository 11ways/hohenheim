package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Backups tab on an instance, gated on {@code backups}.
 *
 * AIDEV-NOTE: restore-to-new stays OPERATOR-ONLY and this tab does not change that.
 * The /manage projection is built over {@code ManageInstanceBackupResource}, whose
 * rowActions() is deliberately empty, so a tenant's rows carry no restore button --
 * and {@code InstanceBackups.restoreToNew} refuses a tenant-originated call anyway.
 * Both halves were already decided; a per-instance VIEW is not the place to reopen them.
 */
public final class InstanceBackupsPage extends InstanceArtifactsPage {

    public static final String SLUG = "backups";

    InstanceBackupsPage(@NonNull InstanceBackupResource resource) {
        super(resource);
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_backups_page"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_backup"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("box-archive"); }

    @Override @NonNull String capability() { return HohenheimAccess.BACKUPS; }
    @Override @NonNull String scope() { return "instance_backups"; }
    @Override @NonNull IntegerField instanceIdField() { return InstanceBackupModel.INSTANCE_ID; }
    @Override @NonNull EnumField statusField() { return InstanceBackupModel.STATUS; }
    @Override @NonNull DateTimeField createdAtField() { return InstanceBackupModel.CREATED_AT; }

    /** A backup carries no operator note; the row is identified by its target and time. */
    @Override
    @NonNull String noteOf(@NonNull Row artifact) {
        return "";
    }

    @Override
    long sizeOf(@NonNull Row artifact) {
        Long bytes = artifact.get(InstanceBackupModel.SIZE_BYTES);
        return bytes != null ? bytes : 0L;
    }

    @Override
    @NonNull String errorOf(@NonNull Row artifact) {
        return blankable(artifact.get(InstanceBackupModel.ERROR));
    }
}
