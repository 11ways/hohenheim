package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The resource shape every backup ledger's rows share (instance backups, database dumps): immutable evidence listed
 * by when it was taken and how it stands, named by its title, deleted by its ledger's operation.
 *
 * AIDEV-NOTE: the list leads with when the row was taken and how it stands, the columns a phone keeps beside the
 * sticky actions; what the ledger describes follows, then the target, and the raw amounts and the remote key wait
 * behind the picker. The ledger's own copy names it; this shape owns no copy keys.
 *
 * @param subject   the ledger's subject type
 * @param slug      the resource's slug
 * @param plural    the resource's label
 * @param singular  the record label
 * @param navOrder  the order in the deploy group (the resource stays out of the nav)
 * @param title     a row's name, read on each reader's own clock
 * @param delete    the operation that removes a row and its artifact
 * @param createdAt when the row was taken
 * @param status    how it stands, its size beneath
 * @param sizeBytes the artifact's size
 * @param targetId  where the artifact went
 * @param remoteKey the artifact's key on its target, also the record page's one field
 * @author Jelle De Loecker
 * @since  0.10.0
 */
record BackupLedgerList(@NonNull SubjectType<Row> subject, @NonNull String slug, @NonNull Microcopy plural,
                        @NonNull Microcopy singular, int navOrder, @NonNull Function<Row, @Nullable Microcopy> title,
                        @NonNull Operation<Row, ?, ?> delete, @NonNull Field<?, ?> createdAt,
                        @NonNull Field<?, ?> status, @NonNull Field<?, ?> sizeBytes, @NonNull Field<?, ?> targetId,
                        @NonNull Field<?, ?> remoteKey) {

    /**
     * The ledger's resource, ready for its parts' actions and tabs.
     *
     * AIDEV-NOTE: the remote key is what a restore is driven by on the target itself. It stays out of the default
     * layout (it is long and noise on a healthy list) but the picker can bring it back, chip and all.
     *
     * @param described   the columns naming what a row holds, after its status
     * @param targetShown whether the target column is in the default layout
     * @param details     the hidden columns after the size, before the remote key
     * @param list        the ledger's own list facts (computed cells, empty text)
     */
    PanelResource.@NonNull Builder<Row> resource(@NonNull Identifier id, @NonNull ResourceParent<Row> parent,
                                                 @NonNull List<ColumnSpec> described, boolean targetShown,
                                                 @NonNull List<ColumnSpec> details,
                                                 @NonNull UnaryOperator<ResourceList.Builder<Row>> list) {
        TableSpec.Builder<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(this.createdAt).build())
            .column(ColumnSpec.fromField(this.status).filterable().subtext(this.sizeBytes.getName()).build());
        described.forEach(table::column);
        table.column(ColumnSpec.fromField(this.targetId)
                .relation(RelationPick.of(this.targetId, BackupTargetModel.MODEL_ID).build())
                .hidden(!targetShown).build())
            .column(ColumnSpec.fromField(this.sizeBytes).hidden().build());
        details.forEach(table::column);
        table.column(ColumnSpec.fromField(this.remoteKey).hidden().copyable().build());
        return PanelResource.builder(id, this.slug, this.subject)
            .label(this.plural)
            .recordLabel(this.singular)
            .icon(Icon.of("box-archive"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(this.navOrder)
            .showInNav(false)
            .parent(parent)
            .reads(ResourceReads.rows().title(this.title))
            // Immutable evidence: no create (born from its ledger's backup) and no update; readers view, actions act.
            .form(ResourceForm.<Row>of(FormSpec.builder().add(this.remoteKey).build())
                .landingTab(RecordOverview.SLUG).build())
            .list(list.apply(ResourceList.rows(table.build()).chrome(ListChrome.MINIMAL)).build())
            .writes(ResourceMutations.rows().delete(this.delete).build());
    }
}
