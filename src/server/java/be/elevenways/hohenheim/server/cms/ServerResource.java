package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.routing.RouteLocales;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.Computed;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.ui.Icon;
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

/**
 * Multi-server Docker host inventory. The implicit {@code local} host always
 * exists and cannot be renamed or removed (only its declared public addresses
 * are editable); remote hosts are reached over SSH.
 * The LIST reads stored host state (health columns + last preflight); the only
 * live daemon contacts are the detail page's overview and the explicit
 * preflight action, both of which persist their outcome.
 */
public final class ServerResource extends RowResource {

    // AIDEV-NOTE: SSH target must be a bare [user@]host[:port] that never starts with '-', so it
    // cannot be parsed as an ssh option (e.g. -oProxyCommand=...) when passed to the ssh argv.
    private static final Pattern SSH_TARGET = Pattern.compile(
        "^(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*@)?(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*|\\[[0-9A-Fa-f:]+\\])(?::[0-9]{1,5})?$");

    private final ServerService serverService = new ServerService();

    private static final StringField LIVE_OVERVIEW = StringField.builder("live_overview")
        .label(HohenheimFormCopy.label("live_overview"))
        .visibleIn(EditView.EDIT)
        .build();

    /**
     * The plain statement of what enrolling grants, at the point of enrolling. Driving a
     * remote Docker daemon IS root-equivalent access to that machine and no mechanism we
     * add changes that; the honest mitigation is that nobody pastes a target without
     * being told.
     */
    private static final StringField TRUST_NOTICE = StringField.builder("trust_notice")
        .label(HohenheimFormCopy.label("trust_notice"))
        .build();

    private static final StringField IDENTITY_PUBLIC_KEY = StringField.builder("identity_public_key")
        .label(HohenheimFormCopy.label("identity_public_key"))
        .help(HohenheimFormCopy.help("identity_public_key"))
        .visibleIn(EditView.EDIT)
        .build();

    private static final StringField HOST_KEY_STATE = StringField.builder("host_key_state")
        .label(HohenheimFormCopy.label("host_key_state"))
        .help(HohenheimFormCopy.help("host_key_state"))
        .visibleIn(EditView.EDIT)
        .build();

    /** The Incus daemon's PINNED server certificate, a different trust relationship. */
    private static final StringField INCUS_CERT_STATE = StringField.builder("incus_cert_state")
        .label(HohenheimFormCopy.label("incus_cert_state"))
        .help(HohenheimFormCopy.help("incus_cert_state"))
        .visibleIn(EditView.EDIT)
        .build();

    /** The client CERTIFICATE an operator enrolls on the daemon (the ssh key's twin). */
    private static final StringField INCUS_CLIENT_CERT = StringField.builder("incus_client_cert")
        .label(HohenheimFormCopy.label("incus_client_cert"))
        .help(HohenheimFormCopy.help("incus_client_cert"))
        .visibleIn(EditView.EDIT)
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
        .add(Computed.of(HOST_KEY_STATE, values -> hostKeyState(String.valueOf(values.get("name"))))
            .dependsOn("name")
            .build())
        .add(Computed.of(IDENTITY_PUBLIC_KEY,
                values -> identityPublicKey(String.valueOf(values.get("name"))))
            .dependsOn("name")
            .build())
        .add(Computed.of(INCUS_CERT_STATE,
                values -> incusCertState(String.valueOf(values.get("name"))))
            .dependsOn("name")
            .build())
        .add(Computed.of(INCUS_CLIENT_CERT,
                values -> incusClientCertificate(String.valueOf(values.get("name"))))
            .dependsOn("name")
            .build())
        .add(Computed.of(LIVE_OVERVIEW, values -> serverOverview(String.valueOf(values.get("name"))))
            .dependsOn("name")
            .build())
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ServerModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.RUNTIME).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.SSH_TARGET).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.ADMISSION).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.POSTURE).filterable().build())
        .column(ColumnSpec.virtual("host_status", Microcopy.of("host_status").withFilter("scope", "server")).build())
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
    @Override public @NonNull String slug() { return "servers"; }
    @Override public @NonNull Model model() { return Models.get(ServerModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 20; }
    @Override public @NonNull Icon icon() { return Icon.of("server"); }


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

    @Override
    public @NonNull List<Row> listRows(TableView.Applied<Row> applied,
                                       @NonNull AccessContext accessContext) {
        this.serverService.ensureLocal();
        return super.listRows(applied, accessContext);
    }

    /** STORED state per host: health columns and the last preflight, never a live probe. */
    private @NonNull String storedStatus(@NonNull Row row) {
        String admission = String.valueOf((Object) row.get(ServerModel.ADMISSION));
        String errorKind = row.get(ServerModel.LAST_ERROR_KIND);
        Instant lastSeen = row.get(ServerModel.LAST_SEEN_AT);
        if (errorKind != null && !errorKind.isBlank()) {
            return hostCopy(Microcopy.of("host_error_state")
                .withArg("kind", errorKind)
                .withArg("admission", admission));
        }
        if (lastSeen == null) {
            return hostCopy(Microcopy.of("host_never_probed").withArg("admission", admission));
        }
        Object capabilities = row.get(ServerModel.CAPABILITIES);
        String label = ServerModel.isIncus(row) ? "Incus" : "Docker";
        String versionKey = ServerModel.isIncus(row) ? "incus_version" : "docker_version";
        String daemon = capabilities instanceof Map<?, ?> map
            && map.get(versionKey) instanceof String version && !version.isBlank()
            ? label + " " + version : label;
        return hostCopy(Microcopy.of("host_stored_state")
            .withArg("docker", daemon)
            .withArg("admission", admission));
    }

    /**
     * The SSH pin as the operator must be able to READ it: which fingerprint we enforce,
     * whether a human ever confirmed it, and -- loudly -- that the host has since offered
     * a different one. An Incus host shows this for its ADMIN lane, which is a different
     * relationship from its daemon certificate below.
     */
    private @NonNull String hostKeyState(@NonNull String name) {
        Row row = Models.get(ServerModel.class).findByName(name);
        if (row == null || !ServerModel.hasSshLane(row)) {
            // An Incus host without a lane is not "local" -- it is a host whose kernel we
            // cannot read, and the copy says which of the two this is.
            return hostCopy(Microcopy.of(row != null && ServerModel.isIncus(row)
                ? "ssh_lane_none" : "host_key_local"));
        }
        return pinState(row, HostTrustSlot.SSH, HostKeys::fingerprintOf,
            "host_key_none", "host_key_confirmed", "host_key_unconfirmed");
    }

    /** The Incus daemon's pinned server certificate, read the same way. */
    private @NonNull String incusCertState(@NonNull String name) {
        Row row = Models.get(ServerModel.class).findByName(name);
        if (row == null || !ServerModel.isIncusHttps(row)) {
            return "";
        }
        return pinState(row, HostTrustSlot.INCUS_TLS, IncusTrust::fingerprintOf,
            "incus_cert_none", "incus_cert_confirmed", "incus_cert_unconfirmed");
    }

    /** One slot's pin state: unpinned, MISMATCH (loudest), confirmed or unconfirmed. */
    private @NonNull String pinState(@NonNull Row row, @NonNull HostTrustSlot slot,
                                     @NonNull UnaryOperator<String> digest,
                                     @NonNull String noneKey, @NonNull String confirmedKey,
                                     @NonNull String unconfirmedKey) {
        String fingerprint = row.get(slot.fingerprint());
        if (fingerprint == null || fingerprint.isBlank()) {
            return hostCopy(Microcopy.of(noneKey));
        }
        String offered = slot.offeredOf(row);
        if (!offered.isBlank()) {
            return hostCopy(Microcopy.of("host_key_mismatch_state")
                .withArg("pinned", fingerprint)
                .withArg("offered", digest.apply(offered)));
        }
        return hostCopy(Microcopy.of(Boolean.TRUE.equals(row.get(slot.verified()))
            ? confirmedKey : unconfirmedKey).withArg("fingerprint", fingerprint));
    }

    /** The public half of this host's OWN ssh key, for the remote's authorized_keys. */
    private @NonNull String identityPublicKey(@NonNull String name) {
        Row row = Models.get(ServerModel.class).findByName(name);
        if (row == null || !ServerModel.hasSshLane(row)) {
            return "";
        }
        String publicKey = row.get(HostTrustSlot.SSH.clientPublic());
        return publicKey != null && !publicKey.isBlank() ? publicKey
            : hostCopy(Microcopy.of("identity_missing"));
    }

    /** This host's client CERTIFICATE, for the daemon's trust store. */
    private @NonNull String incusClientCertificate(@NonNull String name) {
        Row row = Models.get(ServerModel.class).findByName(name);
        if (row == null || !ServerModel.isIncusHttps(row)) {
            return "";
        }
        String certificate = row.get(HostTrustSlot.INCUS_TLS.clientPublic());
        return certificate != null && !certificate.isBlank() ? certificate
            : hostCopy(Microcopy.of("incus_client_missing"));
    }

    /** The detail page's LIVE overview; the probe persists its typed outcome either way. */
    private @NonNull String serverOverview(@NonNull String name) {
        Row row = Models.get(ServerModel.class).findByName(name);
        String label = row != null && ServerModel.isIncus(row) ? "Incus" : "Docker";
        ServerService.Summary summary = this.serverService.probeAndStore(name);
        return summary == null || !summary.reachable()
            ? hostCopy(Microcopy.of("host_daemon_unavailable").withArg("daemon", label))
            : formatSummary(summary, label);
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
            return storedStatus(row);
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
        actions.add(this.preflightAction());
        actions.add(this.admitAction());
        actions.add(this.cordonAction());
        actions.add(this.uncordonAction());
        return actions;
    }

    private static @NonNull Microcopy serverCopy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "server");
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

    /** Run the full preflight and store its report; the toast states the verdict. */
    private @NonNull RowAction<Row> preflightAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "preflight_server"))
            .label(Microcopy.of("preflight").withFilter("scope", "server"))
            .icon(Icon.of("stethoscope"))
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
        // The implicit local host keeps its identity IMMUTABLE (name, target, mode), but
        // its declared public addresses are legitimately operator-set -- without this
        // lane the one host every dev install runs on could never carry an A record.
        if (ServerService.LOCAL.equals(existing.get(ServerModel.NAME))) {
            applyAddressValues(existing, coerced);
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

    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object nameValue = coerced.get("name");
        String name = nameValue != null ? String.valueOf(nameValue).trim()
            : existing != null ? existing.get(ServerModel.NAME) : "";
        if (ServerService.LOCAL.equals(name)) {
            throw Violations.ofField("name", name, CmsSupport.violationText(
                existing == null ? "local_server_reserved" : "local_server_immutable"));
        }
        if (name == null || name.isEmpty() || !name.matches("[a-z0-9][a-z0-9-]*")) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_format"));
        }
        Object targetValue = coerced.get("ssh_target");
        String target = targetValue != null ? String.valueOf(targetValue).trim() : "";
        if (ServerModel.RUNTIME_INCUS.equals(runtimeOf(coerced, existing))) {
            Object urlValue = coerced.get("incus_url");
            String url = urlValue != null ? String.valueOf(urlValue).trim()
                : existing != null ? String.valueOf(
                    (Object) existing.get(ServerModel.INCUS_URL)) : "";
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
}
