package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.host.HostState;
import be.elevenways.hohenheim.host.HostStatusCell;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPostureAcknowledgement;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.incus.IncusReaper;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.hohenheim.server.task.ReapIncusControllers;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.edit.Computed;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteLocales;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Multi-server Docker host inventory. The implicit {@code local} host always
 * exists and cannot be renamed or removed (only its declared public addresses
 * and its posture are editable); remote hosts are reached over SSH.
 * The LIST and the Overview subpage read STORED host state (health columns + last
 * preflight); the only live daemon contacts are the explicit probe and preflight
 * actions, both of which persist their outcome. Rendering any page is side-effect-free.
 */
public final class ServerResource extends RowResource {

    // AIDEV-NOTE: SSH target must be a bare [user@]host[:port] that never starts with '-', so it
    // cannot be parsed as an ssh option (e.g. -oProxyCommand=...) when passed to the ssh argv.
    private static final Pattern SSH_TARGET = Pattern.compile(
        "^(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*@)?(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*|\\[[0-9A-Fa-f:]+\\])(?::[0-9]{1,5})?$");

    private final ServerService serverService = new ServerService();

    /**
     * The plain statement of what enrolling grants, at the point of enrolling. Driving a
     * remote Docker daemon IS root-equivalent access to that machine and no mechanism we
     * add changes that; the honest mitigation is that nobody pastes a target without
     * being told.
     *
     * AIDEV-NOTE: CREATE-only since the overview wave (2026-08-11). This was one of seven
     * Computed pseudo-fields faking a status panel on the EDIT form; the other six are
     * deleted (their data renders structured on {@link ServerOverviewPage}), but this one
     * is consent copy for the enrolment act itself, so it stays exactly where the target
     * gets pasted and nowhere else.
     */
    private static final StringField TRUST_NOTICE = StringField.builder("trust_notice")
        .label(HohenheimFormCopy.label("trust_notice"))
        .visibleIn(EditView.CREATE)
        .build();

    /**
     * Write-only: a pasted Incus trust token triggers {@link IncusTrust#enrollWithToken}
     * on save and is never stored -- tokens are one-use and short-lived by design.
     */
    private static final StringField INCUS_TRUST_TOKEN = StringField.builder("incus_trust_token")
        .label(HohenheimFormCopy.label("incus_trust_token"))
        .help(HohenheimFormCopy.help("incus_trust_token"))
        .build();

    private final FormSpec formSpec = FormSpec.builder()
        .add(ServerModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(ServerModel.RUNTIME))
        .add(ServerModel.SSH_TARGET)
        .add(ServerModel.INCUS_URL)
        .add(INCUS_TRUST_TOKEN)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(ServerModel.POSTURE))
        .add(ServerModel.PUBLIC_IPV4)
        .add(ServerModel.PUBLIC_IPV6)
        .add(Computed.of(TRUST_NOTICE, values -> hostCopy(Microcopy.of(
                ServerModel.RUNTIME_INCUS.equals(values.get("runtime"))
                    ? "trust_notice_body_incus" : "trust_notice_body")))
            .dependsOn("runtime")
            .build())
        // Enrolling a host is naming it, saying how it is reached and trusting it. The
        // public addresses are a fact about the network the operator often fills in later.
        .section(FormSection.advanced(
            ServerModel.PUBLIC_IPV4.getName(),
            ServerModel.PUBLIC_IPV6.getName()))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // AIDEV-NOTE: the public address was stored and never rendered anywhere. It reads
        // under the name rather than as a column, because the SSH target -- which is what
        // an operator actually pastes into a terminal -- keeps the copy chip.
        .column(ColumnSpec.fromField(ServerModel.NAME).filterable().subtext("public_ipv4").build())
        .column(ColumnSpec.fromField(ServerModel.PUBLIC_IPV4).hidden().build())
        .column(ColumnSpec.fromField(ServerModel.RUNTIME).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.SSH_TARGET).filterable().copyable().build())
        .column(ColumnSpec.fromField(ServerModel.ADMISSION).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.POSTURE).filterable().build())
        .column(ColumnSpec.virtual("host_status", Microcopy.of("host_status").withFilter("scope", "server"))
            .renderer("hohenheim:cms/cell/host-status").build())
        .filter(FilterSpec.forField(ServerModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ServerModel.NAME)).build())
        .filter(FilterSpec.forField(ServerModel.RUNTIME, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(ServerModel.RUNTIME)).build())
        .filter(FilterSpec.forField(ServerModel.ADMISSION, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(ServerModel.ADMISSION)).build())
        .filter(FilterSpec.forField(ServerModel.SSH_TARGET, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ServerModel.SSH_TARGET)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "server"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "server"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "server"); }
    @Override public @NonNull String slug() { return "servers"; }
    @Override public @NonNull Model model() { return Models.get(ServerModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    /** A host inventory is counted in handfuls: no saved views, no rule builder, no column gear. */
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** A host is hunted for by its name or by the address something else reported it at. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(ServerModel.NAME, ServerModel.SSH_TARGET, ServerModel.PUBLIC_IPV4, ServerModel.PUBLIC_IPV6);
    }

    // AIDEV-NOTE: the UNGROUPED top block, beside the dashboard -- not a labelled section.
    // A host is not something you deploy, it is what everything else is deployed ONTO, so
    // every labelled group it could join would be a lie about what it is. NavGroup.DEFAULT
    // renders unlabelled and never collapses, which is exactly the "always there" register
    // this and the dashboard need.
    @Override public @NonNull NavGroup navGroup() { return NavGroup.DEFAULT; }
    @Override public int navOrder() { return 10; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "server");
    }
    @Override public @NonNull Icon icon() { return Icon.of("server"); }

    /**
     * The host record's front door is the Overview page; the edit action keeps the form.
     * One declaration drives the tab order, the row title link and the create landing,
     * each derived panel-correctly by the framework.
     */
    @Override
    public @Nullable String landingSubpage() {
        return ServerOverviewPage.SLUG;
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>();
        pages.add(new ServerOverviewPage(this));
        pages.add(new ServerMediaPage());
        pages.addAll(this.frameworkSubpages());
        return pages;
    }

    /** mode is staged by persist/update but is not a form entry; stamp it here. */
    @Override
    public @NonNull Row valuesToRow(@NonNull Map<String, Object> coerced) {
        Row row = super.valuesToRow(coerced);
        if (coerced.get("mode") instanceof String mode) {
            row.set(ServerModel.MODE, mode);
        }
        return row;
    }

    @Override
    public void applyValuesToRow(@NonNull Row row, @NonNull Map<String, Object> coerced) {
        if (coerced.containsKey("name")) {
            row.set(ServerModel.NAME, (String) coerced.get("name"));
        }
        if (coerced.containsKey("runtime") && coerced.get("runtime") instanceof String runtime) {
            row.set(ServerModel.RUNTIME, runtime);
        }
        if (coerced.containsKey("ssh_target")) {
            row.set(ServerModel.SSH_TARGET, (String) coerced.get("ssh_target"));
        }
        if (coerced.containsKey("incus_url")) {
            Object url = coerced.get("incus_url");
            String value = url != null ? String.valueOf(url).trim() : "";
            row.set(ServerModel.INCUS_URL, value.isEmpty() ? null : value);
        }
        if (coerced.containsKey("posture") && coerced.get("posture") instanceof String posture) {
            row.set(ServerModel.POSTURE, posture);
        }
        if (coerced.get("mode") instanceof String mode) {
            row.set(ServerModel.MODE, mode);
        }
        applyAddressValues(row, coerced);
    }

    /** The declared public addresses; blank folds to null (the model hook validates). */
    private static void applyAddressValues(@NonNull Row row, @NonNull Map<String, Object> coerced) {
        if (coerced.containsKey("public_ipv4")) {
            Object value = coerced.get("public_ipv4");
            row.set(ServerModel.PUBLIC_IPV4, value != null ? String.valueOf(value) : null);
        }
        if (coerced.containsKey("public_ipv6")) {
            Object value = coerced.get("public_ipv6");
            row.set(ServerModel.PUBLIC_IPV6, value != null ? String.valueOf(value) : null);
        }
    }

    /**
     * STORED state per host as a structured cell: health columns and the last preflight,
     * never a live probe and never a flattened sentence.
     *
     * AIDEV-NOTE: QUARANTINE is asked FIRST and off its own column, because it was
     * invisible here. This branched on {@code last_error_kind} alone, and since M078 moved
     * the quarantine verdict to {@code quarantined_at}, a successful probe CLEARS the error
     * kind -- so a quarantined-but-reachable host rendered in the list with no quarantine
     * word anywhere. A security state a later success hides is worse than no state.
     * Static and package-reachable on purpose: {@link ServerOverviewPage} renders the SAME
     * cell in its state header, so the list and the overview can never disagree.
     */
    static @NonNull HostStatusCell statusCellOf(@NonNull Row row) {
        Instant lastSeen = row.get(ServerModel.LAST_SEEN_AT);
        String lastSeenIso = lastSeen != null ? lastSeen.toString() : null;
        String daemon = daemonLabelOf(row);
        RelativeTimeWording wording = defaultWording();
        if (row.get(ServerModel.QUARANTINED_AT) != null) {
            return new HostStatusCell(HostState.QUARANTINED, daemon, null, lastSeenIso, wording);
        }
        String errorKind = row.get(ServerModel.LAST_ERROR_KIND);
        if (errorKind != null && !errorKind.isBlank()) {
            return new HostStatusCell(HostState.ERROR, daemon, errorKind, lastSeenIso, wording);
        }
        if (lastSeen == null) {
            return new HostStatusCell(HostState.NEVER_PROBED, daemon, null, null, wording);
        }
        // A host whose last contact is older than the placement bound looks identical to a
        // healthy one otherwise: same version, same admission, no error kind. The refusal
        // an operator would otherwise only meet at deploy is stated where they read.
        if (lapsed(row)) {
            return new HostStatusCell(HostState.SILENT, daemon, null, lastSeenIso, wording);
        }
        return new HostStatusCell(HostState.OK, daemon, null, lastSeenIso, wording);
    }

    /** "Docker 27.1.1" / "Incus 7.3": the daemon label plus its STORED version. */
    private static @NonNull String daemonLabelOf(@NonNull Row row) {
        Object capabilities = row.get(ServerModel.CAPABILITIES);
        String label = ServerModel.isIncus(row) ? "Incus" : "Docker";
        String versionKey = ServerModel.isIncus(row) ? "incus_version" : "docker_version";
        return capabilities instanceof Map<?, ?> map
            && map.get(versionKey) instanceof String version && !version.isBlank()
            ? label + " " + version : label;
    }

    /** Server-default-locale relative-time wording; null when no runtime is booted. */
    private static @Nullable RelativeTimeWording defaultWording() {
        try {
            return RelativeTimeWording.resolve(
                LocaleChain.of(RouteLocales.get().getDefaultLocale()),
                Zenit.getMessageResolver());
        } catch (RuntimeException unbooted) {
            return null;
        }
    }

    /** Whether this host is past the declared contact bound, asked through the GATE itself. */
    private static boolean lapsed(@NonNull Row row) {
        try {
            HostAdmission.requireRecentContact(row);
            return false;
        } catch (Violations refused) {
            return true;
        }
    }

    private static @NonNull String formatSummary(ServerService.@NonNull Summary summary,
                                                 @NonNull String label) {
        String docker = summary.daemonVersion().isBlank() ? label
            : label + " " + summary.daemonVersion();
        String platform = summary.osType();
        if (!summary.architecture().isBlank()) {
            platform = platform.isBlank() ? summary.architecture() : platform + "/" + summary.architecture();
        }
        String operatingSystem = summary.operatingSystem().isBlank() ? platform : summary.operatingSystem();
        if (!platform.isBlank() && !operatingSystem.equals(platform)) {
            operatingSystem += " (" + platform + ")";
        }
        if (operatingSystem.isBlank()) {
            operatingSystem = hostCopy(Microcopy.of("host_unknown_platform"));
        }
        double memoryGib = Math.round(summary.memoryBytes() / 1_073_741_824.0 * 10) / 10.0;
        return hostCopy(Microcopy.of("host_summary")
            .withArg("docker", docker)
            .withArg("os", operatingSystem)
            .withArg("cpus", String.valueOf(summary.cpus()))
            .withArg("memory", String.valueOf(memoryGib))
            .withArg("running", String.valueOf(summary.containersRunning()))
            .withArg("total", String.valueOf(summary.containersTotal()))
            .withArg("images", String.valueOf(summary.images())));
    }

    /**
     * Host stats are computed without a requesting conduit, so they speak the
     * server's default locale.
     */
    private static @NonNull String hostCopy(@NonNull Microcopy microcopy) {
        return microcopy.withFilter("scope", "server")
            .resolve(LocaleChain.of(RouteLocales.get().getDefaultLocale()),
                Zenit.getMessageResolver());
    }

    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if ("host_status".equals(column.name())) {
            return statusCellOf(row);
        }
        return super.cellValue(row, column);
    }

    // -- host lifecycle actions ----------------------------------------------

    /**
     * ONE trust relationship as the admin surface sees it: which slot it pins, when it
     * applies to a row, the ceremony calls, and its own copy. A host record can carry
     * TWO of these at once -- an Incus daemon's TLS certificate and the ssh admin lane
     * kernel-truth verification reads through -- so every action below is built per lane
     * instead of branching on the runtime inside one shared action.
     *
     * AIDEV-NOTE: this replaced a single quartet that dispatched on {@code isIncus}
     * inside its handlers while wearing ssh-only copy ("Scan host key", "the SSH host key
     * this machine presents") on an Incus host. Two relationships, two action sets, two
     * vocabularies -- the mechanism is zenit-cms {@code RowAction}, no bespoke page.
     */
    private record TrustLane(@NonNull String id, @NonNull HostTrustSlot slot,
                             @NonNull Predicate<Row> applies,
                             @NonNull Function<Row, HostKeys.ScanResult> scan,
                             @NonNull Consumer<Row> confirm,
                             @NonNull Consumer<Row> repin,
                             @NonNull Consumer<Row> rotate,
                             @NonNull UnaryOperator<String> digest,
                             @NonNull LaneCopy copy) {
    }

    /** The base microcopy keys of one lane; hints/bodies follow by suffix. */
    private record LaneCopy(@NonNull String scan, @NonNull String confirm,
                            @NonNull String repin, @NonNull String rotate,
                            @NonNull String pinnedToast, @NonNull String unchangedToast,
                            @NonNull String confirmedToast, @NonNull String repinnedToast,
                            @NonNull String rotatedToast, @NonNull String mismatch) {
    }

    /** The ssh host-key lane: a docker host's transport, an Incus host's admin shell. */
    private static final TrustLane SSH_LANE = new TrustLane("host_key", HostTrustSlot.SSH,
        ServerModel::hasSshLane, HostKeys::scanAndPin, HostKeys::confirm, HostKeys::repin,
        HostKeys::rotateIdentity, HostKeys::fingerprintOf,
        new LaneCopy("scan_host_key", "confirm_host_key", "repin_host_key", "rotate_identity",
            "host_key_pinned_toast", "host_key_unchanged_toast", "host_key_confirmed_toast",
            "host_key_repinned_toast", "identity_rotated_toast", "host_key_mismatch"));

    /** The Incus daemon's TLS lane: pinned server certificate + enrolled client certificate. */
    private static final TrustLane INCUS_LANE = new TrustLane("incus_cert",
        HostTrustSlot.INCUS_TLS, ServerModel::isIncusHttps, IncusTrust::scanAndPin,
        IncusTrust::confirm, IncusTrust::repin, IncusTrust::rotateIdentity,
        IncusTrust::fingerprintOf,
        new LaneCopy("scan_incus_cert", "confirm_incus_cert", "repin_incus_cert",
            "rotate_incus_identity", "incus_cert_pinned_toast", "incus_cert_unchanged_toast",
            "incus_cert_confirmed_toast", "incus_cert_repinned_toast",
            "incus_identity_rotated_toast", "incus_cert_mismatch"));

    private static final List<TrustLane> TRUST_LANES = List.of(INCUS_LANE, SSH_LANE);

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        for (TrustLane lane : TRUST_LANES) {
            actions.add(this.scanAction(lane));
            actions.add(this.confirmAction(lane));
            actions.add(this.repinAction(lane));
            actions.add(this.rotateAction(lane));
        }
        actions.add(this.acknowledgePostureAction());
        actions.add(this.probeAction());
        actions.add(this.preflightAction());
        actions.add(this.admitAction());
        actions.add(this.cordonAction());
        actions.add(this.drainAction());
        actions.add(this.uncordonAction());
        actions.add(this.reapControllerObjectsAction());
        return actions;
    }

    /**
     * THE deliberate half of the shared-object reaper: remove what other controllers left
     * on this Incus daemon, INCLUDING the objects whose controller never left a presence
     * stamp -- which the scheduled sweep refuses on principle, because absent evidence is
     * not evidence of absence.
     *
     * AIDEV-NOTE: this is the {@code OrphanActions} bargain, not a shortcut around the
     * sweep's caution. The sweep may only act on proof (a stamp that expired); an operator
     * looking at one named host may also act on judgement. What neither can do is take an
     * object something references -- {@code IncusReaper.reap} re-reads {@code used_by} at
     * removal time and the daemon refuses it a second time on its own.
     */
    private @NonNull RowAction<Row> reapControllerObjectsAction() {
        return RowAction.Invoke.<Row>builder(
                Identifier.of("hohenheim", "reap_controller_objects"))
            .label(serverCopy("reap_controller_objects"))
            .icon(Icon.of("broom"))
            .style(ActionStyle.DESTRUCTIVE)
            .confirmation(ConfirmationSpec.builder()
                .title(serverCopy("reap_controller_objects"))
                .body(serverCopy("reap_controller_objects_confirm"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .visibleFor((row, ctx) -> ServerModel.isIncus(row))
            .handler((row, ctx) -> {
                IncusReaper.Reaped[] reaped = new IncusReaper.Reaped[1];
                ActivityLog.withAction(ActivityLog.ACTION_DELETE, "reap_controller_objects",
                    () -> reaped[0] = ReapIncusControllers.reapIncludingUnstamped(row));
                return CmsActionResult.refreshWithToast(serverCopy("controller_objects_reaped")
                    .withArg("name", row.get(ServerModel.NAME))
                    .withArg("removed", reaped[0].removed().size())
                    .withArg("refused", reaped[0].refused().size()));
            })
            .build();
    }

    private static @NonNull Microcopy serverCopy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "server");
    }

    /**
     * The operator accepts THIS host's shared-container risk, by name and on the record.
     * The posture dropdown declares the intent; this act is what makes the host start
     * taking hostile-tenant containers, and it is the only thing that writes the five
     * acknowledgement columns.
     *
     * AIDEV-NOTE: deliberately NOT a form entry beside the posture select. A second
     * control over the same decision is a second authority, and a warning rendered as
     * field help next to a dropdown is exactly the "boolean hidden in settings" shape the
     * plan's clause refuses. There is also no REVOKE action: changing the posture clears
     * the acknowledgement through the schema hook, so a revoke would be a second path to
     * one state.
     *
     * AIDEV-NOTE: {@code requireTypedConfirmation} is a CLIENT-SIDE mis-click guard and
     * NOTHING else -- the phrase is compared in the dialog template and is never
     * submitted, so a direct POST bypasses it entirely. It is not evidence, it is not the
     * acknowledgement, and it must never be read as either. What actually records the act
     * is {@link HostPostureAcknowledgement#record}, which demands a real actor; what gates
     * the surface is the panel permission plus CSRF.
     */
    private @NonNull RowAction<Row> acknowledgePostureAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "acknowledge_posture"))
            .label(serverCopy("acknowledge"))
            .description(serverCopy("acknowledge_hint"))
            .icon(Icon.of("triangle-exclamation"))
            .style(ActionStyle.DESTRUCTIVE)
            .visibleFor((row, ctx) -> ServerModel.postureNeedsAcknowledgement(row)
                && !ServerModel.postureAcknowledged(row))
            .confirmation(ConfirmationSpec.builder()
                .title(serverCopy("acknowledge"))
                .body(serverCopy("acknowledge_generic"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> ConfirmationSpec.builder()
                .title(serverCopy("acknowledge"))
                .body(serverCopy("acknowledge_body")
                    .withArg("name", row.get(ServerModel.NAME)))
                .style(ActionStyle.DESTRUCTIVE)
                .requireTypedConfirmation(row.get(ServerModel.NAME))
                .build())
            .handler((row, ctx) -> {
                HostPostureAcknowledgement.record(row);
                return CmsActionResult.refreshWithToast(serverCopy("posture_acknowledged")
                    .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    /**
     * Ask the host which identity it offers on this lane and pin it if there is nothing
     * to contradict. A DIFFERENT one never re-pins here: it is stored as evidence and the
     * host is quarantined, because "reconnect and it healed itself" is the exact behaviour
     * a man-in-the-middle needs.
     */
    private @NonNull RowAction<Row> scanAction(@NonNull TrustLane lane) {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "scan_" + lane.id()))
            .label(serverCopy(lane.copy().scan()))
            .description(serverCopy(lane.copy().scan() + "_hint"))
            .icon(Icon.of("fingerprint"))
            // Routine diagnostics live in the row menu: the ONE inline slot the list
            // budget leaves beside Edit belongs to the admission verb (admit/uncordon).
            .inlineInRow(false)
            .visibleFor((row, ctx) -> lane.applies().test(row))
            .handler((row, ctx) -> {
                ensureLaneIdentity(row, lane);
                HostKeys.ScanResult result = lane.scan().apply(row);
                if (result.outcome() == HostKeys.ScanOutcome.MISMATCH) {
                    // Loud and red. The quarantine is already persisted by scanAndPin;
                    // this is the operator-facing half of the same event.
                    // AIDEV-NOTE: a thrown Violations is the only ERROR-level action
                    // outcome zenit-cms offers -- CmsActionResult.Refresh carries a
                    // success toast only, and errorToast() does not refresh.
                    throw Violations.ofForm(CmsSupport.violationText(lane.copy().mismatch())
                        .withArg("name", String.valueOf((Object) row.get(ServerModel.NAME)))
                        .withArg("pinned", String.valueOf(result.previous()))
                        .withArg("offered", result.fingerprint()));
                }
                return CmsActionResult.refreshWithToast(serverCopy(
                        result.outcome() == HostKeys.ScanOutcome.PINNED
                            ? lane.copy().pinnedToast() : lane.copy().unchangedToast())
                    .withArg("fingerprint", result.fingerprint()));
            })
            .build();
    }

    /**
     * The operator states, by typing the fingerprint, that they compared it against what
     * the host's own administrator reports. Nothing else in the product sets this flag.
     */
    private @NonNull RowAction<Row> confirmAction(@NonNull TrustLane lane) {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "confirm_" + lane.id()))
            .label(serverCopy(lane.copy().confirm()))
            .description(serverCopy(lane.copy().confirm() + "_hint"))
            .icon(Icon.of("shield-halved"))
            .inlineInRow(false)
            .visibleFor((row, ctx) -> lane.applies().test(row) && lane.slot().isPinned(row)
                && !Boolean.TRUE.equals(row.get(lane.slot().verified())))
            .confirmation(ConfirmationSpec.builder()
                .title(serverCopy(lane.copy().confirm()))
                .body(serverCopy(lane.copy().confirm() + "_generic"))
                .build())
            .dynamicConfirmation(row -> ConfirmationSpec.builder()
                .title(serverCopy(lane.copy().confirm()))
                .body(serverCopy(lane.copy().confirm() + "_body")
                    .withArg("name", row.get(ServerModel.NAME))
                    .withArg("fingerprint", row.get(lane.slot().fingerprint())))
                .requireTypedConfirmation(row.get(lane.slot().fingerprint()))
                .build())
            .handler((row, ctx) -> {
                lane.confirm().accept(row);
                return CmsActionResult.refreshWithToast(serverCopy(lane.copy().confirmedToast())
                    .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    /**
     * Adopt the identity the host now offers -- the explicit operator act a mismatch
     * demands. Destructive on purpose: the confirmation names both fingerprints and asks
     * for the NEW one to be typed, and the re-pinned host lands unverified, unpreflighted
     * and unadmitted.
     */
    private @NonNull RowAction<Row> repinAction(@NonNull TrustLane lane) {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "repin_" + lane.id()))
            .label(serverCopy(lane.copy().repin()))
            .description(serverCopy(lane.copy().repin() + "_hint"))
            .icon(Icon.of("triangle-exclamation"))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> lane.applies().test(row)
                && !lane.slot().offeredOf(row).isBlank())
            .confirmation(ConfirmationSpec.builder()
                .title(serverCopy(lane.copy().repin()))
                .body(serverCopy(lane.copy().repin() + "_generic"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> {
                String offered = lane.slot().offeredOf(row);
                ConfirmationSpec.Builder builder = ConfirmationSpec.builder()
                    .title(serverCopy(lane.copy().repin()))
                    .style(ActionStyle.DESTRUCTIVE);
                if (offered.isBlank()) {
                    return builder.body(serverCopy(lane.copy().repin() + "_generic")).build();
                }
                String fingerprint = lane.digest().apply(offered);
                return builder
                    .body(serverCopy(lane.copy().repin() + "_body")
                        .withArg("name", row.get(ServerModel.NAME))
                        .withArg("pinned", row.get(lane.slot().fingerprint()))
                        .withArg("offered", fingerprint))
                    .requireTypedConfirmation(fingerprint)
                    .build();
            })
            .handler((row, ctx) -> {
                lane.repin().accept(row);
                return CmsActionResult.refreshWithToast(serverCopy(lane.copy().repinnedToast())
                    .withArg("fingerprint", row.get(lane.slot().fingerprint())));
            })
            .build();
    }

    /** Mint a fresh per-host client credential; the old one stops working immediately. */
    private @NonNull RowAction<Row> rotateAction(@NonNull TrustLane lane) {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "rotate_" + lane.id()))
            .label(serverCopy(lane.copy().rotate()))
            .description(serverCopy(lane.copy().rotate() + "_hint"))
            .icon(Icon.of("key"))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> lane.applies().test(row))
            .confirmation(ConfirmationSpec.builder()
                .title(serverCopy(lane.copy().rotate()))
                .body(serverCopy(lane.copy().rotate() + "_generic"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> ConfirmationSpec.builder()
                .title(serverCopy(lane.copy().rotate()))
                .body(serverCopy(lane.copy().rotate() + "_body")
                    .withArg("name", row.get(ServerModel.NAME)))
                .style(ActionStyle.DESTRUCTIVE)
                .requireTypedConfirmation(row.get(ServerModel.NAME))
                .build())
            .handler((row, ctx) -> {
                lane.rotate().accept(row);
                return CmsActionResult.refreshWithToast(serverCopy(lane.copy().rotatedToast())
                    .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    /**
     * The EXPLICIT live probe. This used to be the {@code live_overview} pseudo-field,
     * which ran {@code probeAndStore} as a side effect of RENDERING the edit form --
     * a page view must never contact a remote daemon. Deliberate now: one click, one
     * probe, the typed outcome persisted and reported either way.
     */
    private @NonNull RowAction<Row> probeAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "probe_server"))
            .label(serverCopy("probe_now"))
            .description(serverCopy("probe_now_hint"))
            .icon(Icon.of("heart-pulse"))
            .inlineInRow(false)
            .handler((row, ctx) -> {
                String name = row.get(ServerModel.NAME);
                String label = ServerModel.isIncus(row) ? "Incus" : "Docker";
                ServerService.Summary summary = this.serverService.probeAndStore(name);
                if (summary == null || !summary.reachable()) {
                    throw Violations.ofForm(CmsSupport.violationText("host_probe_failed")
                        .withArg("name", name)
                        .withArg("kind", summary != null && summary.errorKind() != null
                            ? summary.errorKind() : "unknown"));
                }
                return CmsActionResult.refreshWithToast(serverCopy("host_probe_ok")
                    .withArg("name", name)
                    .withArg("summary", formatSummary(summary, label)));
            })
            .build();
    }

    /** Run the full preflight and store its report; the toast states the verdict. */
    private @NonNull RowAction<Row> preflightAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "preflight_server"))
            .label(Microcopy.of("preflight").withFilter("scope", "server"))
            .icon(Icon.of("stethoscope"))
            .inlineInRow(false)
            .handler((row, ctx) -> {
                String name = row.get(ServerModel.NAME);
                HostPreflight.Report[] report = new HostPreflight.Report[1];
                ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "preflight",
                    () -> report[0] = HostPreflight.runAndStore(name));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of(report[0].passed() ? "preflight_passed" : "preflight_failed")
                        .withFilter("scope", "server")
                        .withArg("name", name));
            })
            .build();
    }

    /** Admit for placement; refused unless the LAST preflight passed. */
    private @NonNull RowAction<Row> admitAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "admit_server"))
            .label(Microcopy.of("admit").withFilter("scope", "server"))
            .icon(Icon.of("circle-check"))
            .visibleFor((row, ctx) ->
                !ServerModel.ADMISSION_ADMITTED.equals(row.get(ServerModel.ADMISSION)))
            .handler((row, ctx) -> {
                HostAdmission.requireAdmittable(row);
                setAdmission(row, ServerModel.ADMISSION_ADMITTED, "admit");
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("host_admitted").withFilter("scope", "server")
                        .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    /** Refuse NEW placement while existing workloads keep running. */
    private @NonNull RowAction<Row> cordonAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "cordon_server"))
            .label(Microcopy.of("cordon").withFilter("scope", "server"))
            .icon(Icon.of("circle-pause"))
            .style(ActionStyle.DESTRUCTIVE)
            .visibleFor((row, ctx) ->
                ServerModel.ADMISSION_ADMITTED.equals(row.get(ServerModel.ADMISSION)))
            .handler((row, ctx) -> {
                setAdmission(row, ServerModel.ADMISSION_CORDONED, "cordon");
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("host_cordoned").withFilter("scope", "server")
                        .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    /**
     * Cold-migrate every live instance off a CORDONED host. A workload that cannot
     * move is refused by name and left untouched; the toast says which -- the drain
     * is only complete when the host holds none.
     */
    private @NonNull RowAction<Row> drainAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "drain_server"))
            .label(Microcopy.of("drain").withFilter("scope", "server"))
            .icon(Icon.of("truck-arrow-right"))
            .style(ActionStyle.DESTRUCTIVE)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("drain").withFilter("scope", "server"))
                .body(Microcopy.of("drain_confirm").withFilter("scope", "server"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .visibleFor((row, ctx) ->
                ServerModel.ADMISSION_CORDONED.equals(row.get(ServerModel.ADMISSION)))
            .handler((row, ctx) -> {
                Integer serverId = row.get(ServerModel.ID);
                // AIDEV-NOTE: no ActivityLog.withAction here, deliberately. A drain writes
                // every instance through fenced updateAll calls, which fire no write hooks,
                // so the wrapper that used to sit here renamed nothing and recorded nothing.
                // InstanceMigrations records the per-instance moves and the host-level drain
                // explicitly instead. Traced 2026-08-07.
                InstanceMigrations.DrainReport report = new InstanceMigrations().drain(serverId);
                if (report.complete()) {
                    return CmsActionResult.refreshWithToast(
                        Microcopy.of("host_drained").withFilter("scope", "server")
                            .withArg("name", row.get(ServerModel.NAME))
                            .withArg("moved", report.moved().size()));
                }
                String held = report.refused().stream()
                    .map(InstanceMigrations.DrainEntry::name)
                    .collect(Collectors.joining(", "));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("host_drain_partial").withFilter("scope", "server")
                        .withArg("name", row.get(ServerModel.NAME))
                        .withArg("moved", report.moved().size())
                        .withArg("refused", report.refused().size())
                        .withArg("held", held));
            })
            .build();
    }

    /** Lift a cordon; the stored preflight verdict must still be green. */
    private @NonNull RowAction<Row> uncordonAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "uncordon_server"))
            .label(Microcopy.of("uncordon").withFilter("scope", "server"))
            .icon(Icon.of("circle-play"))
            .visibleFor((row, ctx) ->
                ServerModel.ADMISSION_CORDONED.equals(row.get(ServerModel.ADMISSION)))
            .handler((row, ctx) -> {
                HostAdmission.requireAdmittable(row);
                setAdmission(row, ServerModel.ADMISSION_ADMITTED, "uncordon");
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("host_admitted").withFilter("scope", "server")
                        .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    private static void setAdmission(@NonNull Row row, @NonNull String admission,
                                     @NonNull String action) {
        ActivityLog.withAction(ActivityLog.ACTION_UPDATE, action, () -> {
            row.set(ServerModel.ADMISSION, admission);
            Models.get(ServerModel.class).save(row);
        });
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        String runtime = runtimeOf(values, null);
        validate(values, null);
        String token = takeTrustToken(values);
        // MODE is the DOCKER lane's transport discriminator; an incus host keeps the
        // default (its transport is declared by incus_url instead).
        values.put("mode", ServerModel.RUNTIME_DOCKER.equals(runtime)
            ? ServerService.MODE_SSH : ServerService.MODE_LOCAL);
        Object id = super.persistRow(values, accessContext);
        // Enrollment mints the host's OWN client identity immediately, so the very
        // first thing the operator sees on the record is the credential to install
        // (authorized_keys, or incus config trust) -- there is never a window in which
        // the controller's ambient identity would do.
        Row created = Models.get(ServerModel.class).findById(id);
        if (created != null) {
            ensureIdentityFor(created);
            enrollTokenIfGiven(created, token);
        }
        ServerOptions.refresh();
        return id;
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        // The implicit local host keeps its identity IMMUTABLE (name, target, mode,
        // runtime), but its declared public addresses AND its posture are legitimately
        // operator-set. Addresses: without that lane the one host every dev install
        // runs on could never carry an A record. Posture: instance placement refuses
        // trusted_only, so a host whose posture cannot be edited can never accept a
        // tenant workload -- on a single-machine install (the primary deployment
        // shape) the local row IS the compute host, and this branch used to swallow
        // the posture silently while the form reported success.
        if (ServerService.LOCAL.equals(existing.get(ServerModel.NAME))) {
            applyAddressValues(existing, coerced);
            if (coerced.get("posture") instanceof String posture) {
                existing.set(ServerModel.POSTURE, posture);
            }
            Models.get(ServerModel.class).save(existing);
            ServerOptions.refresh();
            return;
        }
        Map<String, Object> values = CmsSupport.mutable(coerced);
        String runtime = runtimeOf(values, existing);
        validate(values, existing);
        String token = takeTrustToken(values);
        values.put("mode", ServerModel.RUNTIME_DOCKER.equals(runtime)
            ? ServerService.MODE_SSH : ServerService.MODE_LOCAL);
        super.updateRow(existing, values, accessContext);
        Row saved = Models.get(ServerModel.class).findById(existing.get(ServerModel.ID));
        if (saved != null) {
            enrollTokenIfGiven(saved, token);
        }
        ServerOptions.refresh();
    }

    /** The submitted (or stored) runtime this write is about. */
    private static @NonNull String runtimeOf(@NonNull Map<String, Object> values,
                                             @Nullable Row existing) {
        Object submitted = values.get("runtime");
        if (submitted instanceof String runtime && !runtime.isBlank()) {
            return runtime;
        }
        return existing != null ? ServerModel.runtimeOf(existing) : ServerModel.RUNTIME_DOCKER;
    }

    /** Pull the one-shot trust token OUT of the values so it is never persisted. */
    private static @Nullable String takeTrustToken(@NonNull Map<String, Object> values) {
        Object token = values.remove("incus_trust_token");
        String text = token != null ? String.valueOf(token).trim() : "";
        return text.isEmpty() ? null : text;
    }

    /** Mint the client credential of every lane this record declares and lacks. */
    private static void ensureIdentityFor(@NonNull Row server) {
        for (TrustLane lane : TRUST_LANES) {
            ensureLaneIdentity(server, lane);
        }
    }

    /** Mint ONE lane's client credential when the record declares that lane and has none. */
    private static void ensureLaneIdentity(@NonNull Row server, @NonNull TrustLane lane) {
        if (!lane.applies().test(server)) {
            return;
        }
        String existing = server.get(lane.slot().clientPrivate());
        if (existing != null && !existing.isBlank()) {
            return;
        }
        lane.rotate().accept(server);
    }

    /**
     * Self-enroll on the daemon with a pasted trust token. Requires the pin first --
     * enrolling a credential on a server nobody verified would put the ceremony's
     * steps in an order that defeats it -- so a missing pin is scanned in place
     * (pinned UNVERIFIED, confirm still required for admission).
     */
    private static void enrollTokenIfGiven(@NonNull Row server, @Nullable String token) {
        if (token == null) {
            return;
        }
        if (!ServerModel.isIncusHttps(server)) {
            throw Violations.ofForm(CmsSupport.violationText("incus_token_needs_https"));
        }
        if (!HostTrustSlot.INCUS_TLS.isPinned(server)) {
            IncusTrust.scanAndPin(server);
        }
        IncusTrust.enrollWithToken(server, token);
    }

    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        if (ServerService.LOCAL.equals(existing.get(ServerModel.NAME))) {
            throw Violations.ofForm(CmsSupport.violationText("local_server_undeletable"));
        }
        super.deleteRow(existing, accessContext);
        ServerOptions.refresh();
    }

    /**
     * Name spelling plus the runtime's own address demand.
     *
     * AIDEV-NOTE: every read takes the STORED value when the write does not carry the key.
     * The inline cell lane hands updateRow a map holding EXACTLY ONE entry, and ssh_target
     * was read straight off it: renaming any Docker-runtime host refused with
     * "ssh_target_format", naming an address the operator never touched.
     */
    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        String name = CmsSupport.textOf(coerced, existing, ServerModel.NAME);
        if (ServerService.LOCAL.equals(name)) {
            throw Violations.ofField("name", name, CmsSupport.violationText(
                existing == null ? "local_server_reserved" : "local_server_immutable"));
        }
        if (name.isEmpty() || !name.matches("[a-z0-9][a-z0-9-]*")) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_format"));
        }
        String target = CmsSupport.textOf(coerced, existing, ServerModel.SSH_TARGET);
        if (ServerModel.RUNTIME_INCUS.equals(runtimeOf(coerced, existing))) {
            String url = CmsSupport.textOf(coerced, existing, ServerModel.INCUS_URL);
            try {
                IncusEndpoint.parse(url);
            } catch (IllegalArgumentException bad) {
                throw Violations.ofField("incus_url", url,
                    CmsSupport.violationText("incus_url_format"));
            }
            // The admin lane is OPTIONAL on an Incus host (the daemon is driven over
            // https), but a declared one is held to the same spelling as anywhere else:
            // a target that could be read as an ssh option must never reach an argv.
            if (!target.isEmpty() && !SSH_TARGET.matcher(target).matches()) {
                throw Violations.ofField("ssh_target", target,
                    CmsSupport.violationText("ssh_target_format"));
            }
            return;
        }
        if (target.isEmpty() || !SSH_TARGET.matcher(target).matches()) {
            throw Violations.ofField("ssh_target", target, CmsSupport.violationText("ssh_target_format"));
        }
    }

    /**
     * What the reconciler found on these hosts, demoted out of the sidebar.
     */
    @Override
    public @NonNull List<RelatedPage> relatedPages() {
        return List.of(RelatedPage.toPeer("reconcile-findings"));
    }

}
