package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only audit trail of admin actions.
 */
public final class AuditLogResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(AuditLogModel.CREATED_AT)
        .add(AuditLogModel.USER_LABEL)
        .add(AuditLogModel.ACTION)
        .add(AuditLogModel.RESOURCE_TYPE)
        .add(AuditLogModel.RESOURCE_ID)
        .add(AuditLogModel.RESOURCE_NAME)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(AuditLogModel.CREATED_AT).build())
        .column(ColumnSpec.fromField(AuditLogModel.USER_LABEL).build())
        .column(ColumnSpec.fromField(AuditLogModel.ACTION).build())
        .column(ColumnSpec.fromField(AuditLogModel.RESOURCE_TYPE).build())
        .column(ColumnSpec.fromField(AuditLogModel.RESOURCE_NAME).build())
        .defaultSort(SortSpec.desc(AuditLogModel.CREATED_AT.getName()))
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "audit_log"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.audit_log.plural"); }
    @Override public @NonNull String slug() { return "audit"; }
    @Override public @NonNull Model model() { return Models.get(AuditLogModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return NavGroup.SYSTEM; }
    @Override public int navOrder() { return 90; }
    @Override public @NonNull Icon icon() { return Icon.of("clock-rotate-left"); }
    @Override public boolean creatable() { return false; }

    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (var entry : this.formSpec.entries()) {
            bindings.add(ResourceFieldBinding.of(entry.name(), FieldAccess.alwaysReadonly()));
        }
        return bindings;
    }
}
