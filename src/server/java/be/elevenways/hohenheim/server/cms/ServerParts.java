package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.host.HostState;
import be.elevenways.hohenheim.host.HostStatusCell;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.edit.Computed;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteLocales;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.server.cms.ServerWords.hostCopy;
import static be.elevenways.hohenheim.server.cms.ServerWords.serverCopy;

/**
 * Host inventory parts over stored evidence, explicit trust/lifecycle operations and two-phase enrollment writes.
 * Host identifiers and addresses are verbatim; state words resolve in the viewer's locale.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ServerParts {
    public static final String SLUG = "servers";
    static final StringField INCUS_TRUST_TOKEN = StringField.builder("incus_trust_token")
        .label(HohenheimFormCopy.label("incus_trust_token")).help(HohenheimFormCopy.help("incus_trust_token")).build();
    static final StringField TRUST_NOTICE = StringField.builder("trust_notice")
        .label(HohenheimFormCopy.label("trust_notice")).visibleIn(EditView.CREATE).build();
    static final List<String> LOCAL_IMMUTABLE = List.of(ServerModel.NAME.getName(), ServerModel.RUNTIME.getName(),
        ServerModel.SSH_TARGET.getName(), ServerModel.INCUS_URL.getName());
    public static final Operation<Row, Void, Integer> DELETE = Operation.declare(HohenheimIds.id("delete_server"))
        .label(Microcopy.of("delete").withFilter("scope", "cms"))
        .one(SubjectType.record(ServerModel.MODEL_ID)).gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .result(Integer.class).facts(OperationFact.DESTRUCTIVE).register();
    static {
        OperationHandlers.attach(DELETE).availability((row, access) -> deleteUnavailable(row)).handle(call -> {
            Models.get(ServerModel.class).delete(call.subject());
            ServerOptions.refresh();
            return 1;
        });
    }

    private ServerParts() {}

    public static @NonNull PanelResource<Row> admin() {
        List<PanelAction<Row>> actions = new ArrayList<>(ServerTrustActions.placed());
        actions.addAll(ServerLifecycleActions.placed());
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (String identity : LOCAL_IMMUTABLE) bindings.add(ResourceFieldBinding.of(identity,
            FieldAccess.customRecordAware((access, record) -> local(record)
                ? FieldAccess.Decision.READONLY : FieldAccess.Decision.EDITABLE)));
        bindings.add(ResourceFieldBinding.of(INCUS_TRUST_TOKEN.getName(),
            FieldAccess.customRecordAware((access, record) -> local(record)
                ? FieldAccess.Decision.HIDDEN : FieldAccess.Decision.EDITABLE)));
        return PanelResource.builder(HohenheimIds.id("server"), SLUG, SubjectType.record(ServerModel.MODEL_ID))
            .label(serverCopy("plural")).recordLabel(serverCopy("singular")).description(serverCopy("nav_hint"))
            .navGroup(NavGroup.DEFAULT).navOrder(10).icon(Icon.of("server"))
            .form(ResourceForm.<Row>of(formSpec()).bindings(bindings).landingTab(ServerOverviewState.SLUG).build())
            .list(ResourceList.rows(tableSpec()).chrome(ListChrome.MINIMAL).facets()
                .search(ServerModel.NAME, ServerModel.SSH_TARGET, ServerModel.PUBLIC_IPV4, ServerModel.PUBLIC_IPV6).build())
            .reads(ResourceReads.rows().mapCells((row, column) -> "host_status".equals(column.name()) ? statusCellOf(row) : null))
            .writes(ResourceMutations.rows().create(call -> ServerInventoryWrites.create(call.values()))
                .update(call -> { ServerInventoryWrites.update(call.record(), call.values()); return null; })
                .delete(DELETE).scopeVerifiedBeforeWrite().ownsWriteEnvelope(ResourceVerb.CREATE, ResourceVerb.UPDATE).build())
            .deleteConfirmation(DeleteConfirmation.of(DeleteConfirmation.body(serverCopy("delete_confirm"))))
            .actions(actions)
            .tabs(ResourceTabs.<Row>of(List.of(RecordOverview.<Row>fields(ServerOverviewState.SLUG, serverCopy("overview"))
                    .withoutFields().widgets(ServerOverviewState::widgets), new ServerMediaTab()))
                .withHistory().withContributions())
            .relatedPages(RelatedPage.toPeer("reconcile-findings")).build();
    }

    static boolean local(@Nullable Object record) {
        return record instanceof Row row && ServerService.LOCAL_HOST_NAME.equals(row.get(ServerModel.NAME));
    }

    static FormSpec formSpec() {
        return FormSpec.builder().add(ServerModel.NAME).add(FieldFormEntryRegistry.INSTANCE.deriveEntry(ServerModel.RUNTIME))
            .add(ServerModel.SSH_TARGET).add(ServerModel.INCUS_URL).add(INCUS_TRUST_TOKEN)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(ServerModel.POSTURE))
            .add(ServerModel.PUBLIC_IPV4).add(ServerModel.PUBLIC_IPV6)
            .add(Computed.of(TRUST_NOTICE, values -> hostCopy(serverCopy(ServerModel.RUNTIME_INCUS.equals(values.get("runtime"))
                ? "trust_notice_body_incus" : "trust_notice_body"))).dependsOn("runtime").build())
            .section(FormSection.advanced(ServerModel.PUBLIC_IPV4.getName(), ServerModel.PUBLIC_IPV6.getName())).build();
    }

    static TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(ServerModel.NAME).filterable().subtext("public_ipv4").build())
            .column(ColumnSpec.fromField(ServerModel.PUBLIC_IPV4).hidden().build())
            .column(ColumnSpec.fromField(ServerModel.RUNTIME).filterable().build())
            .column(ColumnSpec.fromField(ServerModel.SSH_TARGET).filterable().copyable().build())
            .column(ColumnSpec.fromField(ServerModel.ADMISSION).filterable().build())
            .column(ColumnSpec.fromField(ServerModel.POSTURE).filterable().build())
            .column(ColumnSpec.virtual("host_status", serverCopy("host_status")).renderer(HohenheimTemplateIds.CELL_HOST_STATUS).build())
            .filter(FilterSpec.forField(ServerModel.NAME, FilterSpec.Kind.TEXT).label(FieldLabels.labelFor(ServerModel.NAME)).build())
            .filter(FilterSpec.forField(ServerModel.RUNTIME, FilterSpec.Kind.SELECT).label(FieldLabels.labelFor(ServerModel.RUNTIME)).build())
            .filter(FilterSpec.forField(ServerModel.ADMISSION, FilterSpec.Kind.SELECT).label(FieldLabels.labelFor(ServerModel.ADMISSION)).build())
            .filter(FilterSpec.forField(ServerModel.SSH_TARGET, FilterSpec.Kind.TEXT).label(FieldLabels.labelFor(ServerModel.SSH_TARGET)).build())
            .build();
    }

    static @Nullable Microcopy deleteUnavailable(Row row) {
        if (local(row)) return serverCopy("delete_local");
        Integer id = row.get(ServerModel.ID);
        Row migrating = id == null ? null : ServerModel.migratingOnto(id).first();
        if (migrating != null) return serverCopy("delete_migrating")
            .withArg("instance", String.valueOf((Object) migrating.get(InstanceModel.NAME)));
        ServerModel.References references = id == null ? null : ServerModel.referencesOf(id);
        return references != null && references.any() ? references.describe(serverCopy("delete_in_use")) : null;
    }

    static @NonNull HostStatusCell statusCellOf(@NonNull Row row) {
        Instant seen = row.get(ServerModel.LAST_SEEN_AT);
        String iso = seen == null ? null : seen.toString();
        String daemon = ServerModel.isIncus(row) ? "Incus" : "Docker";
        Object capabilities = row.get(ServerModel.CAPABILITIES);
        String versionKey = ServerModel.isIncus(row) ? "incus_version" : "docker_version";
        if (capabilities instanceof Map<?, ?> values && values.get(versionKey) instanceof String version && !version.isBlank()) {
            daemon += " " + version;
        }
        RelativeTimeWording wording = null;
        try {
            wording = RelativeTimeWording.resolve(LocaleChain.of(RouteLocales.get().getDefaultLocale()), Zenit.getMessageResolver());
        } catch (RuntimeException unbooted) { /* The stored cell is also readable before boot. */ }
        if (row.get(ServerModel.QUARANTINED_AT) != null) return new HostStatusCell(HostState.QUARANTINED, daemon, null, iso, wording);
        String error = row.get(ServerModel.LAST_ERROR_KIND);
        if (error != null && !error.isBlank()) return new HostStatusCell(HostState.ERROR, daemon, error, iso, wording);
        if (seen == null) return new HostStatusCell(HostState.NEVER_PROBED, daemon, null, null, wording);
        try {
            HostAdmission.requireRecentContact(row);
            return new HostStatusCell(HostState.OK, daemon, null, iso, wording);
        } catch (Violations lapsed) {
            return new HostStatusCell(HostState.SILENT, daemon, null, iso, wording);
        }
    }
}
