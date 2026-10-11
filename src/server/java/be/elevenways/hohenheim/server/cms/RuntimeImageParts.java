package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Runtime catalog parts. Built-ins remain code-owned; names and descriptions are unlocalized user data.
 *
 * AIDEV-NOTE: InstanceCatalogGuards owns write enforcement for every caller, including the plain row form lane.
 * Delete availability is attached to the operation, so offers and direct invocations read the same reason.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class RuntimeImageParts {

    public static final Operation<Row, Void, Integer> DELETE =
            Operation.declare(HohenheimIds.id("delete_runtime_image"))
        .happened(OperationSentences.of("delete_runtime_image"))
        .label(CmsMicrocopy.of("delete"))
        .one(SubjectType.record(RuntimeImageModel.MODEL_ID))
        .gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .result(Integer.class).facts(OperationFact.DESTRUCTIVE).command(CmsCommands.TRANSACTIONAL).register();

    static {
        OperationHandlers.attach(DELETE).applies(RuntimeImageParts::custom)
            .availability((row, access) -> DeleteImpact.runtimeImageInUse(row))
            .handle(call -> {
                Models.get(RuntimeImageModel.class).delete(call.subject());
                return 1;
            });
    }

    private RuntimeImageParts() {}

    public static @NonNull PanelResource<Row> admin() {
        return PanelResource.builder(HohenheimIds.id("runtime_image"), HohenheimSlugs.RUNTIME_IMAGES,
                SubjectType.record(RuntimeImageModel.MODEL_ID))
            .label(HohenheimMicrocopy.RUNTIME_IMAGE.of("plural"))
            .recordLabel(HohenheimMicrocopy.RUNTIME_IMAGE.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.RUNTIME_IMAGE))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).navOrder(35).icon(Icon.of("layer-group"))
            .form(ResourceForm.<Row>of(formSpec()).build())
            .list(ResourceList.rows(tableSpec()).chrome(ListChrome.MINIMAL)
                .search(RuntimeImageModel.NAME, RuntimeImageModel.DESCRIPTION).build())
            .writes(ResourceMutations.rows().create().update().delete(DELETE).build())
            .reads(ResourceReads.rows())
            .authority(ResourceAuthority.<Row>builder().write(null, (row, access) -> custom(row)).build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    static @NonNull FormSpec formSpec() {
        return FormSpec.builder().add(RuntimeImageModel.NAME).add(RuntimeImageModel.DESCRIPTION)
            .add(RuntimeImageModel.DOCKER_IMAGE).add(RuntimeImageModel.INCUS_IMAGE)
            .add(RuntimeImageModel.DEFAULT_COMMAND).add(RuntimeImageModel.DEFAULT_PORT)
            .add(RuntimeImageModel.DEFAULT_BUILD_COMMAND).add(RuntimeImageModel.WORKDIR)
            .add(RuntimeImageModel.SHELL).add(RuntimeImageModel.ENABLED)
            .section(FormSection.advanced(RuntimeImageModel.DEFAULT_COMMAND.getName(),
                RuntimeImageModel.DEFAULT_PORT.getName(), RuntimeImageModel.DEFAULT_BUILD_COMMAND.getName(),
                RuntimeImageModel.WORKDIR.getName(), RuntimeImageModel.SHELL.getName()))
            .build();
    }

    static @NonNull TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(RuntimeImageModel.NAME).filterable().subtext("description").build())
            .column(ColumnSpec.fromField(RuntimeImageModel.DESCRIPTION).hidden().build())
            .column(ColumnSpec.fromField(RuntimeImageModel.DOCKER_IMAGE).copyable().build())
            .column(ColumnSpec.fromField(RuntimeImageModel.INCUS_IMAGE).build())
            .column(ColumnSpec.fromField(RuntimeImageModel.DEFAULT_PORT).build())
            .column(ColumnSpec.fromField(RuntimeImageModel.BUILTIN).build())
            .column(ColumnSpec.fromField(RuntimeImageModel.ENABLED).filterable().build())
            .filter(FilterSpec.leaf(RuntimeImageModel.NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(RuntimeImageModel.NAME)).build()).build();
    }

    static boolean custom(@NonNull Row row) {
        return !Boolean.TRUE.equals(row.get(RuntimeImageModel.BUILTIN));
    }
}
