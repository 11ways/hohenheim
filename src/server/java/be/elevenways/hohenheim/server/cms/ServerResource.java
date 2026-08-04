package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPreflight;
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

    private final FormSpec formSpec = FormSpec.builder()
        .add(ServerModel.NAME)
        .add(ServerModel.SSH_TARGET)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(ServerModel.POSTURE))
        .add(ServerModel.PUBLIC_IPV4)
        .add(ServerModel.PUBLIC_IPV6)
        .add(Computed.of(TRUST_NOTICE, values -> hostCopy(Microcopy.of("trust_notice_body")))
            .dependsOn("name")
            .build())
        .add(Computed.of(HOST_KEY_STATE, values -> hostKeyState(String.valueOf(values.get("name"))))
            .dependsOn("name")
            .build())
        .add(Computed.of(IDENTITY_PUBLIC_KEY,
                values -> identityPublicKey(String.valueOf(values.get("name"))))
            .dependsOn("name")
            .build())
        .add(Computed.of(LIVE_OVERVIEW, values -> serverOverview(String.valueOf(values.get("name"))))
            .dependsOn("name")
            .build())
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ServerModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.MODE).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.SSH_TARGET).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.ADMISSION).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.POSTURE).filterable().build())
        .column(ColumnSpec.virtual("host_status", Microcopy.of("host_status").withFilter("scope", "server")).build())
        .filter(FilterSpec.forField(ServerModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ServerModel.NAME)).build())
        .filter(FilterSpec.forField(ServerModel.MODE, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(ServerModel.MODE)).build())
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
        if (coerced.containsKey("ssh_target")) {
            row.set(ServerModel.SSH_TARGET, (String) coerced.get("ssh_target"));
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
        String docker = capabilities instanceof Map<?, ?> map
            && map.get("docker_version") instanceof String version && !version.isBlank()
            ? "Docker " + version : "Docker";
        return hostCopy(Microcopy.of("host_stored_state")
            .withArg("docker", docker)
            .withArg("admission", admission));
    }

    /**
     * The pin as the operator must be able to READ it: which fingerprint we enforce,
     * whether a human ever confirmed it, and -- loudly -- that the host has since offered
     * a different one.
     */
    private @NonNull String hostKeyState(@NonNull String name) {
        Row row = Models.get(ServerModel.class).findByName(name);
        if (row == null || !ServerModel.MODE_SSH.equals(row.get(ServerModel.MODE))) {
            return hostCopy(Microcopy.of("host_key_local"));
        }
        String fingerprint = row.get(ServerModel.HOST_KEY_FINGERPRINT);
        if (fingerprint == null || fingerprint.isBlank()) {
            return hostCopy(Microcopy.of("host_key_none"));
        }
        String offered = row.get(ServerModel.HOST_KEY_OFFERED);
        if (offered != null && !offered.isBlank()) {
            return hostCopy(Microcopy.of("host_key_mismatch_state")
                .withArg("pinned", fingerprint)
                .withArg("offered", HostKeys.fingerprintOf(offered)));
        }
        return hostCopy(Microcopy.of(Boolean.TRUE.equals(row.get(ServerModel.HOST_KEY_VERIFIED))
            ? "host_key_confirmed" : "host_key_unconfirmed").withArg("fingerprint", fingerprint));
    }

    /** The public half of this host's OWN client key, for the remote's authorized_keys. */
    private @NonNull String identityPublicKey(@NonNull String name) {
        Row row = Models.get(ServerModel.class).findByName(name);
        if (row == null || !ServerModel.MODE_SSH.equals(row.get(ServerModel.MODE))) {
            return "";
        }
        String publicKey = row.get(ServerModel.IDENTITY_PUBLIC_KEY);
        return publicKey != null && !publicKey.isBlank() ? publicKey
            : hostCopy(Microcopy.of("identity_missing"));
    }

    /** The detail page's LIVE overview; the probe persists its typed outcome either way. */
    private @NonNull String serverOverview(@NonNull String name) {
        ServerService.Summary summary = this.serverService.probeAndStore(name);
        return summary == null || !summary.reachable()
            ? hostCopy(Microcopy.of("host_docker_unavailable"))
            : formatSummary(summary);
    }

    private static @NonNull String formatSummary(ServerService.@NonNull Summary summary) {
        String docker = summary.dockerVersion().isBlank() ? "Docker" : "Docker " + summary.dockerVersion();
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

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(this.scanHostKeyAction());
        actions.add(this.confirmHostKeyAction());
        actions.add(this.repinHostKeyAction());
        actions.add(this.rotateIdentityAction());
        actions.add(this.preflightAction());
        actions.add(this.admitAction());
        actions.add(this.cordonAction());
        actions.add(this.uncordonAction());
        return actions;
    }

    private static boolean isRemote(@NonNull Row row) {
        return ServerModel.MODE_SSH.equals(row.get(ServerModel.MODE));
    }

    private static boolean isPinned(@NonNull Row row) {
        String pinned = row.get(ServerModel.HOST_KEY);
        return pinned != null && !pinned.isBlank();
    }

    /**
     * Ask the host which key it offers and pin it if there is nothing to contradict.
     * A DIFFERENT key never re-pins here: it is stored as evidence and the host is
     * quarantined, because "reconnect and it healed itself" is the exact behaviour a
     * man-in-the-middle needs.
     */
    private @NonNull RowAction<Row> scanHostKeyAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "scan_host_key"))
            .label(Microcopy.of("scan_host_key").withFilter("scope", "server"))
            .description(Microcopy.of("scan_host_key_hint").withFilter("scope", "server"))
            .icon(Icon.of("fingerprint"))
            .visibleFor((row, ctx) -> isRemote(row))
            .handler((row, ctx) -> {
                HostKeys.ensureIdentity(row);
                HostKeys.ScanResult result = HostKeys.scanAndPin(row);
                if (result.outcome() == HostKeys.ScanOutcome.MISMATCH) {
                    // Loud and red. The quarantine is already persisted by scanAndPin;
                    // this is the operator-facing half of the same event.
                    // AIDEV-NOTE: a thrown Violations is the only ERROR-level action
                    // outcome zenit-cms offers -- CmsActionResult.Refresh carries a
                    // success toast only, and errorToast() does not refresh.
                    throw Violations.ofForm(CmsSupport.violationText("host_key_mismatch")
                        .withArg("name", String.valueOf((Object) row.get(ServerModel.NAME)))
                        .withArg("pinned", String.valueOf(result.previous()))
                        .withArg("offered", result.fingerprint()));
                }
                return CmsActionResult.refreshWithToast(Microcopy.of(
                        result.outcome() == HostKeys.ScanOutcome.PINNED
                            ? "host_key_pinned_toast" : "host_key_unchanged_toast")
                    .withFilter("scope", "server")
                    .withArg("fingerprint", result.fingerprint()));
            })
            .build();
    }

    /**
     * The operator states, by typing the fingerprint, that they compared it against what
     * the host's own administrator reports. Nothing else in the product sets this flag.
     */
    private @NonNull RowAction<Row> confirmHostKeyAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "confirm_host_key"))
            .label(Microcopy.of("confirm_host_key").withFilter("scope", "server"))
            .description(Microcopy.of("confirm_host_key_hint").withFilter("scope", "server"))
            .icon(Icon.of("shield-halved"))
            .visibleFor((row, ctx) -> isRemote(row) && isPinned(row)
                && !Boolean.TRUE.equals(row.get(ServerModel.HOST_KEY_VERIFIED)))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("confirm_host_key").withFilter("scope", "server"))
                .body(Microcopy.of("confirm_host_key_generic").withFilter("scope", "server"))
                .build())
            .dynamicConfirmation(row -> ConfirmationSpec.builder()
                .title(Microcopy.of("confirm_host_key").withFilter("scope", "server"))
                .body(Microcopy.of("confirm_host_key_body").withFilter("scope", "server")
                    .withArg("name", row.get(ServerModel.NAME))
                    .withArg("fingerprint", row.get(ServerModel.HOST_KEY_FINGERPRINT)))
                .requireTypedConfirmation(row.get(ServerModel.HOST_KEY_FINGERPRINT))
                .build())
            .handler((row, ctx) -> {
                HostKeys.confirm(row);
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("host_key_confirmed_toast").withFilter("scope", "server")
                        .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    /**
     * Adopt the key the host now offers -- the explicit operator act a mismatch demands.
     * Destructive on purpose: the confirmation names both fingerprints and asks for the
     * NEW one to be typed, and the re-pinned host lands unverified, unpreflighted and
     * unadmitted.
     */
    private @NonNull RowAction<Row> repinHostKeyAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "repin_host_key"))
            .label(Microcopy.of("repin_host_key").withFilter("scope", "server"))
            .description(Microcopy.of("repin_host_key_hint").withFilter("scope", "server"))
            .icon(Icon.of("triangle-exclamation"))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> {
                String offered = row.get(ServerModel.HOST_KEY_OFFERED);
                return isRemote(row) && offered != null && !offered.isBlank();
            })
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("repin_host_key").withFilter("scope", "server"))
                .body(Microcopy.of("repin_host_key_generic").withFilter("scope", "server"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> {
                String offered = row.get(ServerModel.HOST_KEY_OFFERED);
                ConfirmationSpec.Builder builder = ConfirmationSpec.builder()
                    .title(Microcopy.of("repin_host_key").withFilter("scope", "server"))
                    .style(ActionStyle.DESTRUCTIVE);
                if (offered == null || offered.isBlank()) {
                    return builder.body(Microcopy.of("repin_host_key_generic")
                        .withFilter("scope", "server")).build();
                }
                String fingerprint = HostKeys.fingerprintOf(offered);
                return builder
                    .body(Microcopy.of("repin_host_key_body").withFilter("scope", "server")
                        .withArg("name", row.get(ServerModel.NAME))
                        .withArg("pinned", row.get(ServerModel.HOST_KEY_FINGERPRINT))
                        .withArg("offered", fingerprint))
                    .requireTypedConfirmation(fingerprint)
                    .build();
            })
            .handler((row, ctx) -> {
                HostKeys.repin(row);
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("host_key_repinned_toast").withFilter("scope", "server")
                        .withArg("fingerprint", row.get(ServerModel.HOST_KEY_FINGERPRINT)));
            })
            .build();
    }

    /** Mint a fresh per-host client key; the old one stops working immediately. */
    private @NonNull RowAction<Row> rotateIdentityAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "rotate_host_identity"))
            .label(Microcopy.of("rotate_identity").withFilter("scope", "server"))
            .description(Microcopy.of("rotate_identity_hint").withFilter("scope", "server"))
            .icon(Icon.of("key"))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> isRemote(row))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("rotate_identity").withFilter("scope", "server"))
                .body(Microcopy.of("rotate_identity_generic").withFilter("scope", "server"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> ConfirmationSpec.builder()
                .title(Microcopy.of("rotate_identity").withFilter("scope", "server"))
                .body(Microcopy.of("rotate_identity_body").withFilter("scope", "server")
                    .withArg("name", row.get(ServerModel.NAME)))
                .style(ActionStyle.DESTRUCTIVE)
                .requireTypedConfirmation(row.get(ServerModel.NAME))
                .build())
            .handler((row, ctx) -> {
                HostKeys.rotateIdentity(row);
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("identity_rotated_toast").withFilter("scope", "server")
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
        validate(values, null);
        values.put("mode", ServerService.MODE_SSH);
        Object id = super.persistRow(values, accessContext);
        // Enrollment mints the host's OWN client key immediately, so the very first
        // thing the operator sees on the record is the public key to install -- there
        // is never a window in which the controller's ambient identity would do.
        Row created = Models.get(ServerModel.class).findById(id);
        if (created != null) {
            HostKeys.ensureIdentity(created);
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
        validate(values, existing);
        values.put("mode", ServerService.MODE_SSH);
        super.updateRow(existing, values, accessContext);
        ServerOptions.refresh();
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
        if (target.isEmpty() || !SSH_TARGET.matcher(target).matches()) {
            throw Violations.ofField("ssh_target", target, CmsSupport.violationText("ssh_target_format"));
        }
    }
}
