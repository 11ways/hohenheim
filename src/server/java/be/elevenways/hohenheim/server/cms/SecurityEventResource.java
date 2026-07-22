package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SecurityEventModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.KnownSecurityEvents;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Read-only analytics over the aggregated security_events table (rows are
 * machine-written upserts; there is nothing an admin should mutate here).
 */
public final class SecurityEventResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(SecurityEventModel.TYPE)
        .add(SecurityEventModel.IP)
        .add(SecurityEventModel.DAY)
        .add(SecurityEventModel.COUNT)
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "security_event"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "security_event"); }
    @Override public @NonNull String slug() { return "security-events"; }
    @Override public @NonNull Model model() { return Models.get(SecurityEventModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 20; }
    @Override public @NonNull Icon icon() { return Icon.of("shield-halved"); }

    @Override public boolean creatable() { return false; }
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    @Override
    public @NonNull TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            // The visible type column shows the KnownSecurityEvents label; the
            // raw dotted type stays available (hidden) and filterable.
            .column(ColumnSpec.virtual("type_label",
                Microcopy.of("event_type").withFilter("scope", "field")).build())
            .column(ColumnSpec.fromField(SecurityEventModel.TYPE)
                .sortable().filterable().hidden().build())
            .columnFromField(SecurityEventModel.IP)
            .columnFromField(SecurityEventModel.DAY)
            .columnFromField(SecurityEventModel.COUNT)
            .columnFromField(SecurityEventModel.LAST_AT)
            .columnFromField(SecurityEventModel.REPORTER_ID)
            .defaultSort(SortSpec.desc("last_at"))
            .build();
    }

    /** The type-label cell resolves through the KnownSecurityEvents vocabulary. */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if ("type_label".equals(column.name())) {
            String type = row.get(SecurityEventModel.TYPE);
            if (type == null) {
                return null;
            }
            for (KnownSecurityEvents.Entry entry : KnownSecurityEvents.entries()) {
                if (type.equals(entry.type()) && entry.description() != null) {
                    // Unresolvable keys still render the raw type via the fallback.
                    return entry.description().withFallback(type);
                }
            }
            return type;
        }
        return super.cellValue(row, column);
    }
}
