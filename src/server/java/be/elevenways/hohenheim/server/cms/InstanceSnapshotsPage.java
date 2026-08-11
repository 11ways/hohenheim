package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

/** Snapshots tab on an instance: this record's snapshot rows, gated on {@code snapshots}. */
public final class InstanceSnapshotsPage extends InstanceArtifactsPage {

    public static final String SLUG = "snapshots";

    InstanceSnapshotsPage(@NonNull InstanceSnapshotResource resource) {
        super(resource);
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_snapshots_page"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_snapshot"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("camera"); }

    @Override @NonNull String capability() { return HohenheimAccess.SNAPSHOTS; }
    @Override @NonNull String scope() { return "instance_snapshots"; }
    @Override @NonNull IntegerField instanceIdField() { return InstanceSnapshotModel.INSTANCE_ID; }
    @Override @NonNull EnumField statusField() { return InstanceSnapshotModel.STATUS; }
    @Override @NonNull DateTimeField createdAtField() { return InstanceSnapshotModel.CREATED_AT; }

    @Override
    @NonNull String noteOf(@NonNull Row artifact) {
        return blankable(artifact.get(InstanceSnapshotModel.NOTE));
    }

    @Override
    long sizeOf(@NonNull Row artifact) {
        Long bytes = artifact.get(InstanceSnapshotModel.TOTAL_BYTES);
        return bytes != null ? bytes : 0L;
    }

    @Override
    @NonNull String errorOf(@NonNull Row artifact) {
        return blankable(artifact.get(InstanceSnapshotModel.ERROR));
    }
}
