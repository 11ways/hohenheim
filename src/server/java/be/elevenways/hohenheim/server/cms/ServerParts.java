package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.host.HostCapacityView;
import be.elevenways.hohenheim.UsageCell;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.host.HostState;
import be.elevenways.hohenheim.host.HostStatusCell;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.DiskSpace;
import be.elevenways.hohenheim.server.host.HostDisk;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.OptionMeter;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
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
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.Availability;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.widget.common.data.UsageData;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Host inventory parts over stored evidence, explicit trust/lifecycle operations and two-phase enrollment writes.
 * Host identifiers and addresses are verbatim; state words resolve in the viewer's locale.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ServerParts {
    private static final String STATE_COLUMN = "state";
    private static final String MEMORY_COLUMN = "memory";
    private static final String DISK_COLUMN = "disk";
    private static final String RUNS_COLUMN = "runs";
    /** Request memo of every host's app and database counts: three queries per rendered list. */
    private static final IdentifierKey<Map<Integer, int[]>> RUNS_COUNTS = IdentifierKey.of("hohenheim", "host_runs");
    /** Form-only, never stored; secret, so it renders masked and a refused submit never echoes it back. */
    static final StringField INCUS_TRUST_TOKEN = StringField.builder("incus_trust_token").secret()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("incus_trust_token"))
        .help(HohenheimMicrocopy.HELP.of("incus_trust_token")).build();
    static final List<String> LOCAL_IMMUTABLE = List.of(ServerModel.NAME.getName(), ServerModel.RUNTIME.getName(),
        ServerModel.SSH_TARGET.getName(), ServerModel.INCUS_URL.getName());
    public static final Operation<Row, Void, Integer> DELETE = Operation.declare(HohenheimIds.id("delete_server"))
        .happened(OperationSentences.of("delete_server"))
        .label(CmsMicrocopy.of("delete"))
        .one(SubjectType.record(ServerModel.REF)).gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .result(Integer.class).facts(OperationFact.DESTRUCTIVE).command(CmsCommands.TRANSACTIONAL).register();
    static {
        OperationHandlers.attach(DELETE)
            .availability(Availability.batch((servers, access) -> DeleteImpact.serversInUse(servers))).handle(call -> {
            IncusTrust.releaseAfterRemoval(call.subject());
            Models.get(ServerModel.class).delete(call.subject());
            ServerOptions.refresh();
            return 1;
        });
    }

    /** THE glyph of a host: its panel's, and the one a host pick's option wears in its standing's tone. */
    private static final Icon HOST_ICON = Icon.of("server");

    private ServerParts() {}

    /**
     * The delete dialog of one host: an Incus host's names the trust entry this controller holds on its daemon, which
     * the removal gives back (IncusTrust.releaseAfterRemoval), and the command that removes it by hand when the host
     * cannot be reached then.
     */
    public static @NonNull ConfirmationSpec deleteBody(@NonNull Row host) {
        String fingerprint = IncusTrust.ownEntryOf(host);
        return DeleteConfirmation.body(fingerprint == null ? HohenheimMicrocopy.SERVER.of("delete_confirm")
            : HohenheimMicrocopy.SERVER.of("delete_confirm_incus_trust").withArg("fingerprint", fingerprint));
    }

    public static @NonNull PanelResource<Row> admin() {
        List<PanelAction<Row>> actions = new ArrayList<>(ServerTrustActions.placed());
        actions.addAll(ServerLifecycleActions.placed());
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (String identity : LOCAL_IMMUTABLE) bindings.add(ResourceFieldBinding.of(identity,
            FieldAccess.customRecordAware((access, record) -> local(record)
                ? FieldAccess.Decision.READONLY : FieldAccess.Decision.EDITABLE)
                // Never hidden: every stored server reads its identity.
                .readableWhere(ctx -> null)));
        bindings.add(ResourceFieldBinding.of(INCUS_TRUST_TOKEN.getName(),
            FieldAccess.customRecordAware((access, record) -> local(record)
                ? FieldAccess.Decision.HIDDEN : FieldAccess.Decision.EDITABLE)));
        return PanelResource.builder(HohenheimIds.id("server"), HohenheimSlugs.SERVERS, SubjectType
            .record(ServerModel.REF))
            .label(HohenheimMicrocopy.SERVER.of("plural")).recordLabel(HohenheimMicrocopy.SERVER.of("singular"))
            .description(HohenheimMicrocopy.SERVER.of("nav_hint"))
            .navGroup(NavGroup.DEFAULT).navOrder(40).icon(HOST_ICON)
            .form(ResourceForm.<Row>of(formSpec()).bindings(bindings).landingTab(RecordOverview.SLUG)
                .tabLabel(HohenheimMicrocopy.APP_OVERVIEW.of("configuration")).build())
            .list(hostList(tableSpec()))
            .reads(ResourceReads.rows().mapCells((row, column) -> "host_status".equals(column.name())
                    ? statusCellOf(row) : null))
            .writes(ResourceMutations.rows().create(call -> ServerInventoryWrites.create(call.values(), call.merged()))
                .update(call -> {
                    ServerInventoryWrites.update(call.record(), call.values(), call.merged());
                    return null;
                })
                .delete(DELETE).scopeVerifiedBeforeWrite().ownsWriteEnvelope(ResourceVerb.CREATE,
                        ResourceVerb.UPDATE).build())
            .deleteConfirmation(DeleteConfirmation.<Row>of(DeleteConfirmation.body(
                    HohenheimMicrocopy.SERVER.of("delete_confirm")))
                .forRow((host, request) -> deleteBody(host)))
            .actions(actions)
            .tabs(ResourceTabs.<Row>of(List.of(RecordOverview.<Row>fields(RecordOverview.SLUG,
                HohenheimMicrocopy.SERVER.of("overview"))
                    .withoutFields().widgets(ServerOverviewState::widgets), new ServerMediaTab()))
                .withHistory().historyInStrip().withContributions())
            .relatedPages(RelatedPage.toPeer(HohenheimSlugs.RECONCILE_FINDINGS)).build();
    }

    static boolean local(@Nullable Object record) {
        return record instanceof Row row && ServerService.LOCAL_HOST_NAME.equals(row.get(ServerModel.NAME));
    }

    static FormSpec formSpec() {
        FormSpec.Builder form = FormSpec.builder().createTitle(HohenheimMicrocopy.SERVER.of("create_title"))
            .add(ServerModel.NAME).add(FieldFormEntryRegistry.INSTANCE.deriveEntry(ServerModel.RUNTIME))
            .add(ServerModel.SSH_TARGET).add(ServerModel.INCUS_URL).add(INCUS_TRUST_TOKEN)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(ServerModel.POSTURE))
            .add(ServerModel.PUBLIC_IPV4).add(ServerModel.PUBLIC_IPV6).add(ServerModel.LAN_PARENT);
        // What enrolling grants depends on the runtime chosen. One notice per runtime, each shown only under its own,
        // because a Computed is resolved once on the server and never again when the runtime changes in the browser.
        for (String runtime : ServerModel.RUNTIME.getValues().keySet()) {
            StringField notice = StringField.builder("trust_notice_" + runtime)
                .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("trust_notice")).visibleIn(EditView.CREATE).build();
            form.add(Computed.of(notice, values -> CmsSupport.resolvedTextOrDefault(
                    HohenheimMicrocopy.SERVER.of("trust_notice_body").withFilter("runtime", runtime)))
                    .dependsOn(ServerModel.RUNTIME.getName()).build())
                .showWhen(notice.getName(), ServerModel.RUNTIME.getName(), runtime);
        }
        // Only an Incus host puts a machine on its local network.
        form.showWhen(ServerModel.LAN_PARENT.getName(), ServerModel.RUNTIME.getName(), ServerModel.RUNTIME_INCUS);
        return form.section(FormSection.advanced(ServerModel.PUBLIC_IPV4.getName(),
                ServerModel.PUBLIC_IPV6.getName(), ServerModel.LAN_PARENT.getName())).build();
    }

    /**
     * What each machine takes, what it holds and what it runs, with the fix for one that cannot run
     * apps yet in the band above. The public address, runtime, ssh target and the raw admission token stay searchable
     * or filterable behind the picker.
     */
    static TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            // The daemon and when it was last seen sit under the name, as a card line does.
            .column(ColumnSpec.fromField(ServerModel.NAME).filterable().subtext("host_status").build())
            .column(ColumnSpec.fromField(ServerModel.PUBLIC_IPV4).hidden().build())
            .column(ColumnSpec.virtual(STATE_COLUMN, HohenheimMicrocopy.HOST_LIST.of("state_column"))
                .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build())
            .column(ColumnSpec.virtual("host_status", HohenheimMicrocopy.SERVER.of("host_status"))
                .renderer(HohenheimTemplateIds.CELL_HOST_STATUS).hidden().build())
            .column(ColumnSpec.virtual(MEMORY_COLUMN, HohenheimMicrocopy.HOST_LIST.of("memory_column"))
                .renderer(HohenheimTemplateIds.CELL_USAGE).build())
            .column(ColumnSpec.virtual(DISK_COLUMN, HohenheimMicrocopy.HOST_LIST.of("disk_column"))
                .renderer(HohenheimTemplateIds.CELL_USAGE).build())
            // What it runs sits under who may run here, as a card ends; a sixth column pushed the table
            // under the pinned row actions at 1440px.
            .column(ColumnSpec.fromField(ServerModel.POSTURE).filterable().subtext(RUNS_COLUMN).build())
            .column(ColumnSpec.virtual(RUNS_COLUMN, HohenheimMicrocopy.HOST_LIST.of("runs_column")).hidden().build())
            .column(ColumnSpec.fromField(ServerModel.RUNTIME).filterable().hidden().build())
            .column(ColumnSpec.fromField(ServerModel.SSH_TARGET).filterable().copyable().hidden().build())
            .column(ColumnSpec.fromField(ServerModel.ADMISSION).filterable().hidden().build())
            .filter(FilterSpec.leaf(ServerModel.NAME, CoreTypes.CONTAINS)
                    .label(FieldLabels.labelFor(ServerModel.NAME)).build())
            .filter(FilterSpec.leaf(ServerModel.RUNTIME, CoreTypes.EQUALS)
                    .label(FieldLabels.labelFor(ServerModel.RUNTIME)).build())
            .filter(FilterSpec.leaf(ServerModel.ADMISSION, CoreTypes.EQUALS)
                    .label(FieldLabels.labelFor(ServerModel.ADMISSION)).build())
            .filter(FilterSpec.leaf(ServerModel.SSH_TARGET, CoreTypes.CONTAINS)
                    .label(FieldLabels.labelFor(ServerModel.SSH_TARGET)).build())
            .build();
    }

    private static @NonNull ResourceList<Row> hostList(@NonNull TableSpec<Row> table) {
        return ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets()
            .search(ServerModel.NAME, ServerModel.SSH_TARGET, ServerModel.PUBLIC_IPV4, ServerModel.PUBLIC_IPV6)
            .widgets(scope -> AttentionCollector.band(AttentionCollector.hosts()))
            .computed(Objects.requireNonNull(table.column(STATE_COLUMN)), (row, request) -> stateCellOf(row))
            .computed(Objects.requireNonNull(table.column(MEMORY_COLUMN)), (row, request) -> memoryCellOf(row))
            .computed(Objects.requireNonNull(table.column(DISK_COLUMN)), (row, request) -> diskCellOf(row))
            .computed(Objects.requireNonNull(table.column(RUNS_COLUMN)), ServerParts::runsCellOf)
            .build();
    }

    /** What the host takes and why, in the words of its one verdict ({@link HostVerdict}). */
    static @NonNull StateLineCell stateCellOf(@NonNull Row server) {
        return HostVerdict.of(server).cell();
    }

    /** Booked memory against the bookable budget, from the same ledger the host's overview and placement read. */
    static @NonNull UsageCell memoryCellOf(@NonNull Row server) {
        HostCapacityView capacity = capacityOf(server);
        if (!measured(capacity)) {
            return new UsageCell(false, 0, HohenheimMicrocopy.HOST_LIST.of(capacity != null && capacity.stale()
                ? "reading_stale" : "reading_unmeasured"));
        }
        int percent = UsageData.percentOf(capacity.bookedMb(), capacity.budgetMb());
        return new UsageCell(true, percent, bookedText(capacity));
    }

    /**
     * The host's disk, the filesystem holding its instance data ({@link HostDisk}): used against its size. A host
     * never measured, or measured too long ago, draws no bar, only the words saying so.
     */
    static @NonNull UsageCell diskCellOf(@NonNull Row server) {
        HostDisk.Reading reading = HostDisk.storedOf(server);
        if (reading == null || !reading.fresh()) {
            return new UsageCell(false, 0,
                HohenheimMicrocopy.HOST_LIST.of(reading == null ? "reading_unmeasured" : "reading_stale"));
        }
        DiskSpace space = reading.space();
        int percent = UsageData.percentOf(space.usedBytes(), space.totalBytes());
        return new UsageCell(true, percent, diskUsedText(space));
    }

    /** @return a disk's used bytes against its size in words, the one way the list and the host pick say it */
    private static @NonNull Microcopy diskUsedText(@NonNull DiskSpace space) {
        return HohenheimMicrocopy.HOST_LIST.of("disk_used")
            .withArg("used", space.usedBytes()).withArg("total", space.totalBytes());
    }

    /** @return the host's capacity ledger, null for a host without an id */
    private static @Nullable HostCapacityView capacityOf(@NonNull Row server) {
        Integer id = server.get(ServerModel.ID);
        return id == null ? null : InstanceCapacity.viewOf(server, id);
    }

    /** @return whether the ledger carries a measured, positive budget to judge against */
    private static boolean measured(@Nullable HostCapacityView capacity) {
        return capacity != null && capacity.measured() && capacity.budgetMb() > 0;
    }

    private static @NonNull Microcopy bookedText(@NonNull HostCapacityView capacity) {
        return HohenheimMicrocopy.HOST_LIST.of("memory_booked")
            .withArg("booked", ByteUnit.MEGABYTE.bytes(capacity.bookedMb()))
            .withArg("budget", ByteUnit.MEGABYTE.bytes(capacity.budgetMb()));
    }

    /**
     * A host as a host pick offers it: its name, then the Hosts list's facts, read from the same verdict, capacity
     * ledger and disk reading the list draws: the standing's badge words as its line and, when measured, its booked
     * memory and then its disk as the option's meters (an unmeasured host says so in its line instead, a disk without a
     * fresh reading draws no meter); the host glyph wears the standing's tone, since a select option's tone shows on its
     * glyph alone.
     */
    public static @NonNull FieldOption<Integer> hostOption(@NonNull Row server) {
        StateLineCell state = stateCellOf(server);
        FieldOption<Integer> option = FieldOption.of((Integer) server.get(ServerModel.ID),
                Microcopy.literal(String.valueOf((Object) server.get(ServerModel.NAME))))
            .withIcon(HOST_ICON.name())
            .withTone(state.variant());
        HostCapacityView capacity = capacityOf(server);
        if (!measured(capacity)) {
            return option.withDescription(HohenheimMicrocopy.HOST_LIST.of("host_option_facts")
                .withArg("state", state.label()).withArg("memory", memoryCellOf(server).text()));
        }
        FieldOption<Integer> metered = option.withDescription(state.label()).withMeter(new OptionMeter(
            capacity.bookedMb(), capacity.budgetMb(), HohenheimMicrocopy.HOST_LIST.of("host_option_memory")
                .withArg("memory", bookedText(capacity))));
        HostDisk.Reading disk = HostDisk.storedOf(server);
        if (disk == null || !disk.fresh()) {
            return metered;
        }
        DiskSpace space = disk.space();
        return metered.withMeter(new OptionMeter(space.usedBytes(), space.totalBytes(),
            HohenheimMicrocopy.HOST_LIST.of("host_option_disk").withArg("disk", diskUsedText(space))));
    }

    /** How many apps and managed databases the host runs, counted once per rendered list. */
    private static @NonNull String runsCellOf(@NonNull Row server, @NonNull PanelRequest request) {
        Conduit conduit = request.conduit();
        Map<Integer, int[]> counts = conduit.memo(RUNS_COUNTS, ServerParts::runsCounts);
        int[] runs = counts.getOrDefault(server.get(ServerModel.ID), new int[2]);
        Microcopy text = runs[0] == 0 && runs[1] == 0 ? HohenheimMicrocopy.HOST_LIST.of("runs_nothing")
            : HohenheimMicrocopy.HOST_LIST.of("runs_count")
                .withArg("apps", HohenheimMicrocopy.HOST_LIST.of("apps_count").withArg("count", runs[0]))
                .withArg("databases", HohenheimMicrocopy.HOST_LIST.of("databases_count").withArg("count", runs[1]));
        return text.asString(conduit);
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
            counts.computeIfAbsent(ServerModel.canonicalServerId(stack.get(StackModel.SERVER_ID)),
                    id -> new int[2])[0]++;
        }
        for (Row database : Models.get(DatabaseModel.class).find().all()) {
            counts.computeIfAbsent(ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID)),
                id -> new int[2])[1]++;
        }
        return counts;
    }

    static @NonNull HostStatusCell statusCellOf(@NonNull Row row) {
        Instant seen = row.get(ServerModel.LAST_SEEN_AT);
        String iso = seen == null ? null : seen.toString();
        String daemon = ServerModel.isIncus(row) ? "Incus" : "Docker";
        Object capabilities = row.get(ServerModel.CAPABILITIES);
        String versionKey = ServerModel.isIncus(row) ? "incus_version" : "docker_version";
        if (capabilities instanceof Map<?, ?> values && values.get(versionKey) instanceof String version
                && !version.isBlank()) {
            daemon += " " + version;
        }
        if (row.get(ServerModel.QUARANTINED_AT) != null) return new HostStatusCell(HostState.QUARANTINED, daemon, null,
            iso);
        String error = row.get(ServerModel.LAST_ERROR_KIND);
        if (error != null && !error.isBlank()) {
            return new HostStatusCell(HostState.ERROR, daemon, HostProbe.FailureKind.labelOf(error), iso);
        }
        if (seen == null) return new HostStatusCell(HostState.NEVER_PROBED, daemon, null, null);
        try {
            HostAdmission.requireRecentContact(row);
            return new HostStatusCell(HostState.OK, daemon, null, iso);
        } catch (Violations lapsed) {
            return new HostStatusCell(HostState.SILENT, daemon, null, iso);
        }
    }
}
