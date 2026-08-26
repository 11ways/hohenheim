package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A site's guarded path prefixes: which folders demand an extra access list. Hidden from
 * the sidebar -- reached through a site's Protected paths tab.
 */
public class ProtectedPathResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(ProtectedPathModel.SITE_ID, SiteModel.MODEL_ID).build())
        .add(ProtectedPathModel.PATH)
        // Creating a list from inside this pick stays off for the SiteResource reason: an
        // empty list going live here would GUARD NOTHING while reading as protection.
        .add(RelationPick.of(ProtectedPathModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID)
            .creatable(false).build())
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ProtectedPathModel.PATH).filterable().copyable().build())
        .column(ColumnSpec.fromField(ProtectedPathModel.ACCESS_LIST_ID)
            .label(FieldLabels.labelForRelation(ProtectedPathModel.ACCESS_LIST_ID))
            .relation(RelationPick.of(ProtectedPathModel.ACCESS_LIST_ID,
                AccessListModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(ProtectedPathModel.SITE_ID)
            .label(FieldLabels.labelForRelation(ProtectedPathModel.SITE_ID))
            .relation(RelationPick.of(ProtectedPathModel.SITE_ID, SiteModel.MODEL_ID).build())
            .build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "protected_path"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "protected_path"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "protected_path"); }
    @Override public @NonNull String slug() { return "protected-paths"; }
    @Override public @NonNull Model model() { return Models.get(ProtectedPathModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 32; }
    @Override public @NonNull Icon icon() { return Icon.of("lock"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(ProtectedPathModel.PATH);
    }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("sites",
            row -> row.get(ProtectedPathModel.SITE_ID)).tab("protected-paths");
    }

    /** Guarding a folder is authority over the site it belongs to, like a domain row. */
    @Override
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.reachesRecord(accessContext, SiteModel.MODEL_ID,
            record.get(ProtectedPathModel.SITE_ID), HohenheimAccess.MANAGE);
    }

    /** The site's Protected paths tab links here with ?site_id= so the pick is preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        Integer siteId = CmsSupport.parsedInt(conduit.getQueryParam("site_id"));
        if (siteId != null) {
            values.put(ProtectedPathModel.SITE_ID.getName(), siteId);
        }
        return Map.copyOf(values);
    }

    // AIDEV-NOTE: no quickCreate() bar. The row is a path PLUS a list pick, the bar's v1
    // entry subset cannot render a RelationPick, and a bar carrying only the path would
    // produce rows the invariant below must refuse (no list = guards nothing). The tab's
    // "Protect a path" button opens the create form with the site prefilled instead.

    private static volatile boolean protectionInvariantInstalled;

    /**
     * Install THE protected-path invariant on the model write pipeline: the path stored
     * canonically (the dispatcher's own spelling), a usable prefix, a list, and one row
     * per (site, path) -- for every writer, not just the CMS form, for the reason
     * {@link SiteDomainResource#installRouteInvariant} spells out.
     */
    public static synchronized void installProtectionInvariant() {
        if (protectionInvariantInstalled) {
            return;
        }
        protectionInvariantInstalled = true;
        ProtectedPathModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            if (row.has(ProtectedPathModel.PATH.getName())) {
                Object raw = row.get(ProtectedPathModel.PATH);
                String canonical = SiteDispatcher.normalizeRoutePath(
                    raw != null ? String.valueOf(raw) : null);
                // Canonical null means "/" or blank: guarding everything is the site's own
                // access list, and a stored null would silently guard NOTHING here.
                if (canonical == null) {
                    throw Violations.ofField(ProtectedPathModel.PATH.getName(), raw,
                        CmsSupport.violationText("protected_path_required"));
                }
                if (!Objects.equals(raw, canonical)) {
                    row.set(ProtectedPathModel.PATH, canonical);
                }
            }
            refuseIncompleteOrDuplicate(row);
        });
    }

    /** Site and list required, and the (site, path) pair unclaimed. */
    private static void refuseIncompleteOrDuplicate(@NonNull Row row) {
        Model model = Models.get(ProtectedPathModel.class);
        Row stored = row.has(ProtectedPathModel.ID.getName())
            && row.get(ProtectedPathModel.ID) != null
            ? model.findById(row.get(ProtectedPathModel.ID)) : null;

        Object siteIdValue = effective(row, stored, ProtectedPathModel.SITE_ID);
        if (!(siteIdValue instanceof Integer siteId)) {
            throw Violations.ofField(ProtectedPathModel.SITE_ID.getName(), siteIdValue,
                CmsSupport.violationText("site_required"));
        }
        Object listId = effective(row, stored, ProtectedPathModel.ACCESS_LIST_ID);
        if (!(listId instanceof Integer)) {
            throw Violations.ofField(ProtectedPathModel.ACCESS_LIST_ID.getName(), listId,
                CmsSupport.violationText("access_list_required"));
        }
        Object path = effective(row, stored, ProtectedPathModel.PATH);
        if (path == null || String.valueOf(path).isBlank()) {
            throw Violations.ofField(ProtectedPathModel.PATH.getName(), path,
                CmsSupport.violationText("protected_path_required"));
        }
        Object ownId = stored != null ? stored.get(ProtectedPathModel.ID) : null;
        for (Row candidate : model.find()
                .where(ProtectedPathModel.SITE_ID.eq(siteId))
                .and(ProtectedPathModel.PATH.eq(String.valueOf(path))).all()) {
            if (!Objects.equals(candidate.get(ProtectedPathModel.ID), ownId)) {
                throw Violations.ofField(ProtectedPathModel.PATH.getName(), path,
                    CmsSupport.violationText("protected_path_taken"));
            }
        }
    }

    /** The value the write will END UP with, reading the stored row on a partial update. */
    private static @Nullable Object effective(@NonNull Row row, @Nullable Row stored,
                                              @NonNull Field<?, ?> field) {
        if (row.has(field.getName())) {
            return row.get(field.getName());
        }
        return stored != null ? stored.get(field.getName()) : field.getDefaultValue();
    }
}
