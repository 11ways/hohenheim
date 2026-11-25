package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HostTrustLane;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;


/**
 * The host trust ceremony's one lane table and its scan/confirm/repin/rotate operation placements.
 *
 * AIDEV-NOTE: split out of ServerResource. HostEnrolment mints through THIS table too, so no second
 * "which lane applies and how is its credential minted" vocabulary exists. An Incus host may carry both TLS and SSH
 * relationships; neither shares the other's pin or key. The former row action handlers are replaced and deleted.
 *
 * AIDEV-NOTE: a mismatching scan stores evidence and quarantines; it never repins silently. Repin asks the digest of
 * the actually offered material. A typed phrase guards a mis-click, not authority or evidence of comparison.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class HostTrustActions {
    record TrustLane(@NonNull String id, @NonNull HostTrustSlot slot, @NonNull Predicate<Row> applies,
                     @NonNull Function<Row, HostKeys.ScanResult> scan, @NonNull Consumer<Row> confirm,
                     @NonNull Consumer<Row> repin, @NonNull Consumer<Row> rotate,
                     @NonNull UnaryOperator<String> digest, @NonNull LaneCopy copy) {}

    record LaneCopy(@NonNull String scan, @NonNull String confirm, @NonNull String repin, @NonNull String rotate,
                    @NonNull String pinnedToast, @NonNull String unchangedToast, @NonNull String confirmedToast,
                    @NonNull String repinnedToast, @NonNull String rotatedToast, @NonNull String mismatch) {}

    static final TrustLane SSH_LANE = new TrustLane(HostTrustLane.SSH.key(), HostTrustSlot.SSH,
        HostModel::hasSshLane, HostKeys::scanAndPin, HostKeys::confirm, HostKeys::repin,
        HostKeys::rotateIdentity, HostKeys::fingerprintOf,
        new LaneCopy("scan_host_key", "confirm_host_key", "repin_host_key", "rotate_identity",
            "host_key_pinned_toast", "host_key_unchanged_toast", "host_key_confirmed_toast",
            "host_key_repinned_toast", "identity_rotated_toast", "host_key_mismatch"));
    static final TrustLane INCUS_LANE = new TrustLane(HostTrustLane.INCUS.key(), HostTrustSlot.INCUS_TLS,
        HostModel::isIncusHttps, IncusTrust::scanAndPin, IncusTrust::confirm, IncusTrust::repin,
        IncusTrust::rotateIdentity, IncusTrust::fingerprintOf,
        new LaneCopy("scan_incus_cert", "confirm_incus_cert", "repin_incus_cert", "rotate_incus_identity",
            "incus_cert_pinned_toast", "incus_cert_unchanged_toast", "incus_cert_confirmed_toast",
            "incus_cert_repinned_toast", "incus_identity_rotated_toast", "incus_cert_mismatch"));
    static final List<TrustLane> TRUST_LANES = List.of(INCUS_LANE, SSH_LANE);

    /** The fingerprint a person compared out of band, as an earlier answer printed it; left out, the key is read. */
    static final StringField FINGERPRINT = StringField.builder("fingerprint")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("trust_fingerprint"))
        .help(HohenheimMicrocopy.HELP.of("trust_fingerprint")).build();

    /** @param fingerprint what the caller compared, null to read and pin the presented key */
    record Compared(@Nullable String fingerprint) {}

    /** Why the panel's single trust steps are not offered to assistants: their one tool step is {@link #trust}. */
    private static final String PERSON_PINS = "a host's identity is pinned, confirmed or repinned through the one"
        + " trust step that compares the fingerprint the caller gives (trust_host_key, trust_incus_cert)";
    private static final List<PanelAction<Row>> PLACED = declarePlaced();

    /** Reads, confirms or adopts a host's SSH host key by the fingerprint the caller compared. */
    static final Operation<Row, Compared, Microcopy> TRUST_SSH = trust(SSH_LANE);

    /** Reads, confirms or adopts an Incus daemon's certificate by the fingerprint the caller compared. */
    static final Operation<Row, Compared, Microcopy> TRUST_INCUS = trust(INCUS_LANE);

    /** Makes a new SSH client key for one host. */
    static final Operation<Row, Void, Microcopy> ROTATE_SSH = HostLifecycleActions.declared("rotate_" + SSH_LANE.id());

    /** Makes a new Incus client certificate for one host. */
    static final Operation<Row, Void, Microcopy> ROTATE_INCUS =
        HostLifecycleActions.declared("rotate_" + INCUS_LANE.id());

    private HostTrustActions() {}
    static @NonNull List<PanelAction<Row>> placed() { return PLACED; }

    private static List<PanelAction<Row>> declarePlaced() {
        List<PanelAction<Row>> actions = new ArrayList<>();
        for (TrustLane lane : TRUST_LANES) {
            ConfirmationSpec confirm = ConfirmationSpec.verb(HohenheimMicrocopy.SERVER.of(lane.copy().confirm()),
                HohenheimMicrocopy.SERVER.of(lane.copy().confirm() + "_generic"), ActionStyle.DEFAULT);
            ConfirmationSpec repin = ConfirmationSpec.verb(HohenheimMicrocopy.SERVER.of(lane.copy().repin()),
                HohenheimMicrocopy.SERVER.of(lane.copy().repin() + "_generic"), ActionStyle.DESTRUCTIVE);
            ConfirmationSpec rotate = ConfirmationSpec.verb(HohenheimMicrocopy.SERVER.of(lane.copy().rotate()),
                HohenheimMicrocopy.SERVER.of(lane.copy().rotate() + "_generic"), ActionStyle.DESTRUCTIVE);
            actions.add(HostLifecycleActions.place("scan_" + lane.id(),
                HohenheimMicrocopy.SERVER.of(lane.copy().scan()), HostLifecycleActions.kept(PERSON_PINS),
                row -> scanned(row, lane), lane.applies())
                .description(HohenheimMicrocopy.SERVER.of(lane.copy().scan() + "_hint"))
                .icon(Icon.of("fingerprint")).inlineInRow(false).build());
            actions.add(HostLifecycleActions.place("confirm_" + lane.id(),
                HohenheimMicrocopy.SERVER.of(lane.copy().confirm()), HostLifecycleActions.kept(PERSON_PINS),
                row -> confirmed(row, lane), row -> lane.applies().test(row) && lane.slot().isPinned(row)
                && !Boolean.TRUE.equals(row.get(lane.slot().verified())))
                .description(HohenheimMicrocopy.SERVER.of(lane.copy().confirm() + "_hint"))
                .icon(Icon.of("shield-halved"))
                .inlineInRow(false)
                .confirmation(confirm)
                .dynamicConfirmation(row -> confirm.withBody(HohenheimMicrocopy.SERVER
                    .of(lane.copy().confirm() + "_body").withArg("name", row.get(HostModel.NAME))
                    .withArg("fingerprint", row.get(lane.slot().fingerprint())))
                    .withTypedConfirmation(row.get(lane.slot().fingerprint())))
                .build());
            actions.add(HostLifecycleActions.place("repin_" + lane.id(),
                HohenheimMicrocopy.SERVER.of(lane.copy().repin()), HostLifecycleActions.kept(PERSON_PINS),
                row -> repinned(row, lane), row -> lane.applies().test(row) && !lane.slot().offeredOf(row).isBlank())
                .description(HohenheimMicrocopy.SERVER.of(lane.copy().repin() + "_hint"))
                .icon(Icon.of("triangle-exclamation"))
                .style(ActionStyle.DESTRUCTIVE).inlineInRow(false).confirmation(repin)
                .dynamicConfirmation(row -> {
                    String offered = lane.slot().offeredOf(row);
                    if (offered.isBlank()) return repin;
                    String fingerprint = lane.digest().apply(offered);
                    return repin.withBody(HohenheimMicrocopy.SERVER
                        .of(lane.copy().repin() + "_body").withArg("name", row.get(HostModel.NAME))
                        .withArg("pinned", row.get(lane.slot().fingerprint())).withArg("offered", fingerprint))
                        .withTypedConfirmation(fingerprint);
                }).build());
            // A new client identity is no comparison: the panel's button and the assistant's tool run the same step.
            actions.add(HostLifecycleActions.place("rotate_" + lane.id(),
                HohenheimMicrocopy.SERVER.of(lane.copy().rotate()), builder -> builder.description(
                    HohenheimMicrocopy.SERVER.of(lane.copy().rotate() + "_hint")), row -> {
                lane.rotate().accept(row);
                return HohenheimMicrocopy.SERVER.of(lane.copy().rotatedToast())
                    .withArg("name", row.get(HostModel.NAME));
            }, lane.applies()).description(HohenheimMicrocopy.SERVER.of(lane.copy().rotate() + "_hint"))
                .icon(Icon.of("key")).style(ActionStyle.DESTRUCTIVE).inlineInRow(false)
                .confirmation(rotate)
                .dynamicConfirmation(row -> rotate.withBody(HohenheimMicrocopy.SERVER
                    .of(lane.copy().rotate() + "_body").withArg("name", row.get(HostModel.NAME)))
                    .withTypedConfirmation(row.get(HostModel.NAME))).build());
        }
        return List.copyOf(actions);
    }

    /**
     * The lane's one assistant trust step: no fingerprint reads and pins the presented key as the Scan button does;
     * the pinned fingerprint confirms it as Confirm does; the fingerprint of the key the host now presents adopts it
     * as Re-pin does. The comparison the panel's typed confirmation guards is checked here, on the server.
     *
     * AIDEV-NOTE: the panel keeps its three buttons (each a typed ceremony); this step runs the same three writers
     * ({@link #scanned}, {@link #confirmed}, {@link #repinned}), chosen by what the caller compared.
     */
    private static @NonNull Operation<Row, Compared, Microcopy> trust(@NonNull TrustLane lane) {
        String id = "trust_" + lane.id();
        Operation<Row, Compared, Microcopy> operation = Operation.declare(HohenheimIds.id(id))
            .happened(OperationSentences.of(id))
            .label(HohenheimMicrocopy.HOHENHEIM.of(id))
            .description(HohenheimMicrocopy.HOHENHEIM.of(id + "_description"))
            .one(SubjectType.record(HostModel.REF)).gate(HohenheimSources.OPERATOR)
            .input(OperationInput.of(FormSpec.builder().add(FINGERPRINT).build(), Compared.class,
                values -> new Compared(values.get(FINGERPRINT))))
            .command(CmsCommands.EXTERNAL)
            .result(Microcopy.class).register();
        OperationHandlers.attach(operation).applies(lane.applies()::test).handle(call -> trusted(call.subject(), lane,
            Objects.requireNonNull(call.input()).fingerprint()));
        return operation;
    }

    /**
     * @throws Violations naming {@code fingerprint} when it is neither the pinned one nor that of the key the host
     *     presents now; nothing is trusted then
     */
    private static @NonNull Microcopy trusted(@NonNull Row row, @NonNull TrustLane lane,
                                              @Nullable String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return scanned(row, lane);
        String given = fingerprint.trim();
        if (lane.slot().isPinned(row) && given.equals(row.get(lane.slot().fingerprint()))) {
            return confirmed(row, lane);
        }
        String offered = lane.slot().offeredOf(row);
        if (!offered.isBlank() && given.equals(lane.digest().apply(offered))) return repinned(row, lane);
        throw Violations.ofField(FINGERPRINT.getName(), given, HohenheimMicrocopy.VIOLATIONS
            .of("trust_fingerprint_unknown").withArg("fingerprint", given)
            .withArg("name", String.valueOf((Object) row.get(HostModel.NAME))));
    }

    /** Reads the key the host presents and pins it when none is; a different one is refused and kept as evidence. */
    private static @NonNull Microcopy scanned(@NonNull Row row, @NonNull TrustLane lane) {
        ensureLaneIdentity(row, lane);
        HostKeys.ScanResult result = lane.scan().apply(row);
        if (result.outcome() == HostKeys.ScanOutcome.MISMATCH) {
            // AIDEV-NOTE: success refreshes only; a Violations refusal is the loud error half of quarantine.
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of(lane.copy().mismatch())
                .withArg("name", String.valueOf((Object) row.get(HostModel.NAME)))
                .withArg("pinned", String.valueOf(result.previous())).withArg("offered", result.fingerprint()));
        }
        return HohenheimMicrocopy.SERVER.of(result.outcome() == HostKeys.ScanOutcome.PINNED
            ? lane.copy().pinnedToast() : lane.copy().unchangedToast())
            .withArg("fingerprint", result.fingerprint());
    }

    /** Records the out-of-band comparison of the pinned fingerprint; a pin confirmed before stays as it is. */
    private static @NonNull Microcopy confirmed(@NonNull Row row, @NonNull TrustLane lane) {
        if (!Boolean.TRUE.equals(row.get(lane.slot().verified()))) lane.confirm().accept(row);
        return HohenheimMicrocopy.SERVER.of(lane.copy().confirmedToast()).withArg("name", row.get(HostModel.NAME));
    }

    /** Adopts the key the host presents now: unconfirmed and unadmitted again. */
    private static @NonNull Microcopy repinned(@NonNull Row row, @NonNull TrustLane lane) {
        lane.repin().accept(row);
        return HohenheimMicrocopy.SERVER.of(lane.copy().repinnedToast())
            .withArg("fingerprint", row.get(lane.slot().fingerprint()));
    }

    static void ensureLaneIdentity(@NonNull Row server, @NonNull TrustLane lane) {
        if (!lane.applies().test(server)) return;
        String existing = server.get(lane.slot().clientPrivate());
        if (existing == null || existing.isBlank()) lane.rotate().accept(server);
    }
}
