package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Volume declaration operations; identifiers and paths are verbatim, labels and refusals localized.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class VolumeOperations {
    public static final IntegerField QUOTA_MB = IntegerField.builder().name("quota_mb")
        .label(HohenheimFormCopy.label("quota_mb")).help(HohenheimFormCopy.help("volume_quota_mb"))
        .suffix("MB").build();
    public static final FormSpec FORM = FormSpec.builder().add(InstanceVolumeModel.INSTANCE_ID)
        .add(InstanceVolumeModel.NAME).add(InstanceVolumeModel.CONTAINER_PATH)
        .add(QUOTA_MB).add(InstanceVolumeModel.EXCLUSIVE).build();

    public record Declaration(@Nullable Integer instance_id, @Nullable String name,
                              @Nullable String container_path, @Nullable Integer quota_mb,
                              @Nullable Boolean exclusive) {}

    private static final OperationInput<Declaration> INPUT = OperationInput.of(FORM, Declaration.class,
        values -> new Declaration(values.get(InstanceVolumeModel.INSTANCE_ID), values.get(InstanceVolumeModel.NAME),
            values.get(InstanceVolumeModel.CONTAINER_PATH), values.get(QUOTA_MB),
            values.get(InstanceVolumeModel.EXCLUSIVE)));
    private static final SubjectType<Row> SUBJECT = SubjectType.record(InstanceVolumeModel.MODEL_ID);
    public static final Operation<Void, Declaration, Integer> CREATE = Operation.declare(HohenheimIds.id("declare_volume"))
        .label(Microcopy.of("singular").withFilter("scope", "instance_volume"))
        .noSubject().gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS)
            .parentCapability("instance_id", InstanceModel.MODEL_ID, HohenheimCapabilities.CONFIG))
        .input(INPUT).result(Integer.class).register();
    public static final Operation<Row, Declaration, Void> UPDATE = Operation.declare(HohenheimIds.id("redeclare_volume"))
        .label(Microcopy.of("singular").withFilter("scope", "instance_volume"))
        .one(SUBJECT).gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
        .input(INPUT).patchable().register();
    public static final Operation<Row, Void, Void> DESTROY = Operation.declare(HohenheimIds.id("destroy_volume"))
        .label(Microcopy.of("destroy").withFilter("scope", "instance_volume"))
        .icon(Icon.of("trash-can")).one(SUBJECT)
        .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
        .facts(OperationFact.DESTRUCTIVE, OperationFact.REACHES_OUTSIDE).register();

    private VolumeOperations() {}
}
