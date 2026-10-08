package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.host.HostCapacityView;
import be.elevenways.hohenheim.host.HostMemoryCell;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.text.ByteText;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.host.HostState;
import be.elevenways.hohenheim.host.HostStatusCell;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.protoblast.common.typed.CoreTypes;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private static final String STATE_COLUMN = "state";
    private static final String MEMORY_COLUMN = "memory";
    private static final String RUNS_COLUMN = "runs";
    private static final long MEBIBYTE = 1024L * 1024L;
    /** Request memo of every host's app and database counts: three queries per rendered list. */
    private static final IdentifierKey<Map<Integer, int[]>> RUNS_COUNTS = IdentifierKey.of("hohenheim", "host_runs");
    static final StringField INCUS_TRUST_TOKEN = StringField.builder("incus_trust_token")
        .label(HohenheimFormCopy.label("incus_trust_token")).help(HohenheimFormCopy.help("incus_trust_token")).build();
    static final StringField TRUST_NOTICE = StringField.builder("trust_notice")
        .label(HohenheimFormCopy.label("trust_notice")).visibleIn(EditView.CREATE).build();
    static final List<String> LOCAL_IMMUTABLE = List.of(ServerModel.NAME.getName(), ServerModel.RUNTIME.getName(),
        ServerModel.SSH_TARGET.getName(), ServerModel.INCUS_URL.getName());
    public static final Operation<Row, Void, Integer> DELETE = Operation.declare(HohenheimIds.id("delete_server"))
        .happened(OperationSentences.of("delete_server"))
        .label(Microcopy.of("delete").withFilter("scope", "cms"))
        .one(SubjectType.record(ServerModel.MODEL_ID)).gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .result(Integer.class).facts(OperationFact.DESTRUCTIVE).command(CmsCommands.TRANSACTIONAL).register();
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
            .navGroup(NavGroup.DEFAULT).navOrder(40).icon(Icon.of("server"))
            .form(ResourceForm.<Row>of(formSpec()).bindings(bindings).landingTab(ServerOverviewState.SLUG)
                .tabLabel(AppOverview.copy("configuration")).build())
            .list(hostList(tableSpec()))
            .reads(ResourceReads.rows().mapCells((row, column) -> "host_status".equals(column.name()) ? statusCellOf(row) : null))
            .writes(ResourceMutations.rows().create(call -> ServerInventoryWrites.create(call.values()))
                .update(call -> { ServerInventoryWrites.update(call.record(), call.values()); return null; })
                .delete(DELETE).scopeVerifiedBeforeWrite().ownsWriteEnvelope(ResourceVerb.CREATE, ResourceVerb.UPDATE).build())
            .deleteConfirmation(DeleteConfirmation.of(DeleteConfirmation.body(serverCopy("delete_confirm"))))
            .actions(actions)
            .tabs(ResourceTabs.<Row>of(List.of(RecordOverview.<Row>fields(ServerOverviewState.SLUG, serverCopy("overview"))
                    .withoutFields().widgets(ServerOverviewState::widgets), new ServerMediaTab()))
                .withHistory().historyInStrip().withContributions())
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

    /**
     * Board Hosts: what each machine takes, what it holds and what it runs, with the fix for one that cannot run
     * apps yet in the band above. The public address, runtime, ssh target and the raw admission token stay searchable
     * or filterable behind the picker.
     */
    static TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            // The daemon and when it was last seen sit under the name, as the board's card line does.
            .column(ColumnSpec.fromField(ServerModel.NAME).filterable().subtext("host_status").build())
            .column(ColumnSpec.fromField(ServerModel.PUBLIC_IPV4).hidden().build())
            .column(ColumnSpec.virtual(STATE_COLUMN, listCopy("state_column"))
                .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build())
            .column(ColumnSpec.virtual("host_status", serverCopy("host_status"))
                .renderer(HohenheimTemplateIds.CELL_HOST_STATUS).hidden().build())
            .column(ColumnSpec.virtual(MEMORY_COLUMN, listCopy("memory_column"))
                .renderer(HohenheimTemplateIds.CELL_HOST_MEMORY).build())
            // What it runs sits under who may run here, as the board's card ends; a sixth column pushed the table
            // under the pinned row actions at 1440px.
            .column(ColumnSpec.fromField(ServerModel.POSTURE).filterable().subtext(RUNS_COLUMN).build())
            .column(ColumnSpec.virtual(RUNS_COLUMN, listCopy("runs_column")).hidden().build())
            .column(ColumnSpec.fromField(ServerModel.RUNTIME).filterable().hidden().build())
            .column(ColumnSpec.fromField(ServerModel.SSH_TARGET).filterable().copyable().hidden().build())
            .column(ColumnSpec.fromField(ServerModel.ADMISSION).filterable().hidden().build())
            .filter(FilterSpec.leaf(ServerModel.NAME, CoreTypes.CONTAINS).label(FieldLabels.labelFor(ServerModel.NAME)).build())
            .filter(FilterSpec.leaf(ServerModel.RUNTIME, CoreTypes.EQUALS).label(FieldLabels.labelFor(ServerModel.RUNTIME)).build())
            .filter(FilterSpec.leaf(ServerModel.ADMISSION, CoreTypes.EQUALS).label(FieldLabels.labelFor(ServerModel.ADMISSION)).build())
            .filter(FilterSpec.leaf(ServerModel.SSH_TARGET, CoreTypes.CONTAINS).label(FieldLabels.labelFor(ServerModel.SSH_TARGET)).build())
            .build();
    }

    private static @NonNull ResourceList<Row> hostList(@NonNull TableSpec<Row> table) {
        return ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets()
            .search(ServerModel.NAME, ServerModel.SSH_TARGET, ServerModel.PUBLIC_IPV4, ServerModel.PUBLIC_IPV6)
            .widgets(scope -> AttentionCollector.band(AttentionCollector.hosts()))
            .computed(Objects.requireNonNull(table.column(STATE_COLUMN)), (row, request) -> stateCellOf(row))
            .computed(Objects.requireNonNull(table.column(MEMORY_COLUMN)), (row, request) -> memoryCellOf(row))
            .computed(Objects.requireNonNull(table.column(RUNS_COLUMN)), ServerParts::runsCellOf)
            .build();
    }

    /** What the host takes and why, in the words of its one verdict ({@link HostVerdict}). */
    static @NonNull StateLineCell stateCellOf(@NonNull Row server) {
        return HostVerdict.of(server).cell();
    }

    /** Booked memory against the bookable budget, from the same ledger the host's overview and placement read. */
    static @NonNull HostMemoryCell memoryCellOf(@NonNull Row server) {
        Integer id = server.get(ServerModel.ID);
        HostCapacityView capacity = id == null ? null : InstanceCapacity.viewOf(server, id);
        if (capacity == null || !capacity.measured() || capacity.budgetMb() <= 0) {
            return new HostMemoryCell(false, 0, listCopy(capacity != null && capacity.stale()
                ? "memory_stale" : "memory_unmeasured"));
        }
        int percent = (int) Math.min(100, Math.round(100.0 * capacity.bookedMb() / capacity.budgetMb()));
        return new HostMemoryCell(true, percent, listCopy("memory_booked")
            .withArg("booked", sizeOfMegabytes(capacity.bookedMb()))
            .withArg("budget", sizeOfMegabytes(capacity.budgetMb())));
    }

    /** @return a host ledger's megabyte count as a size, the one way the list and the host page say memory */
    static @NonNull String sizeOfMegabytes(int megabytes) {
        return ByteText.human(megabytes * MEBIBYTE);
    }

    /** How many apps and managed databases the host runs, counted once per rendered list. */
    private static @NonNull String runsCellOf(@NonNull Row server, @NonNull PanelRequest request) {
        Conduit conduit = request.conduit();
        Map<Integer, int[]> counts = conduit.getAttribute(RUNS_COUNTS);
        if (counts == null) {
            counts = runsCounts();
            try {
                conduit.setAttribute(RUNS_COUNTS, counts);
            } catch (UnsupportedOperationException attributeless) {
                // An attribute-less conduit counts again per row.
            }
        }
        int[] runs = counts.getOrDefault(server.get(ServerModel.ID), new int[2]);
        Microcopy text = runs[0] == 0 && runs[1] == 0 ? listCopy("runs_nothing")
            : listCopy("runs_count").withArg("apps", listCopy("apps_count").withArg("count", runs[0]))
                .withArg("databases", listCopy("databases_count").withArg("count", runs[1]));
        return text.resolve(conduit.getLocales(), conduit.getMessageResolver());
    }

    /**
     * Host id to {apps, databases}: the workloads and stacks the Apps list counts as apps (never a generated database
     * container, engine or stack service) and the managed database records placed there.
     */
    private static @NonNull Map<Integer, int[]> runsCounts() {
        Map<Integer, int[]> counts = new HashMap<>();
        for (Row instance : Models.get(InstanceModel.class).find().all()) {
            if (!InstanceParts.isGenerated(instance)) {
                counts.computeIfAbsent(ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID)),
                    id -> new int[2])[0]++;
            }
        }
        for (Row stack : Models.get(StackModel.class).find().all()) {
            counts.computeIfAbsent(ServerModel.canonicalServerId(stack.get(StackModel.SERVER_ID)), id -> new int[2])[0]++;
        }
        for (Row database : Models.get(DatabaseModel.class).find().all()) {
            counts.computeIfAbsent(ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID)),
                id -> new int[2])[1]++;
        }
        return counts;
    }

    static @NonNull Microcopy listCopy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "host_list");
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
        if (error != null && !error.isBlank()) {
            return new HostStatusCell(HostState.ERROR, daemon, HostProbe.FailureKind.labelOf(error), iso, wording);
        }
        if (seen == null) return new HostStatusCell(HostState.NEVER_PROBED, daemon, null, null, wording);
        try {
            HostAdmission.requireRecentContact(row);
            return new HostStatusCell(HostState.OK, daemon, null, iso, wording);
        } catch (Violations lapsed) {
            return new HostStatusCell(HostState.SILENT, daemon, null, iso, wording);
        }
    }
}
