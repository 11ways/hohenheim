package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HostTrustLane;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static be.elevenways.hohenheim.server.cms.ServerResource.serverCopy;

/**
 * The host trust ceremony as the admin surface sees it: the two trust lanes a host record can carry and the
 * scan/confirm/repin/rotate row actions built per lane.
 *
 * AIDEV-NOTE: split out of ServerResource (review finding, 2026-09). The lanes are also what
 * {@link HostEnrolment} mints client identities through, so the "which lane applies, how is its
 * credential minted" table has exactly one home.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class ServerTrustActions {

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
    record TrustLane(@NonNull String id, @NonNull HostTrustSlot slot,
                     @NonNull Predicate<Row> applies,
                     @NonNull Function<Row, HostKeys.ScanResult> scan,
                     @NonNull Consumer<Row> confirm,
                     @NonNull Consumer<Row> repin,
                     @NonNull Consumer<Row> rotate,
                     @NonNull UnaryOperator<String> digest,
                     @NonNull LaneCopy copy) {
    }

    /** The base microcopy keys of one lane; hints/bodies follow by suffix. */
    record LaneCopy(@NonNull String scan, @NonNull String confirm,
                    @NonNull String repin, @NonNull String rotate,
                    @NonNull String pinnedToast, @NonNull String unchangedToast,
                    @NonNull String confirmedToast, @NonNull String repinnedToast,
                    @NonNull String rotatedToast, @NonNull String mismatch) {
    }

    /** The ssh host-key lane: a docker host's transport, an Incus host's admin shell. */
    static final TrustLane SSH_LANE = new TrustLane(HostTrustLane.SSH.key(), HostTrustSlot.SSH,
        ServerModel::hasSshLane, HostKeys::scanAndPin, HostKeys::confirm, HostKeys::repin,
        HostKeys::rotateIdentity, HostKeys::fingerprintOf,
        new LaneCopy("scan_host_key", "confirm_host_key", "repin_host_key", "rotate_identity",
            "host_key_pinned_toast", "host_key_unchanged_toast", "host_key_confirmed_toast",
            "host_key_repinned_toast", "identity_rotated_toast", "host_key_mismatch"));

    /** The Incus daemon's TLS lane: pinned server certificate + enrolled client certificate. */
    static final TrustLane INCUS_LANE = new TrustLane(HostTrustLane.INCUS.key(),
        HostTrustSlot.INCUS_TLS, ServerModel::isIncusHttps, IncusTrust::scanAndPin,
        IncusTrust::confirm, IncusTrust::repin, IncusTrust::rotateIdentity,
        IncusTrust::fingerprintOf,
        new LaneCopy("scan_incus_cert", "confirm_incus_cert", "repin_incus_cert",
            "rotate_incus_identity", "incus_cert_pinned_toast", "incus_cert_unchanged_toast",
            "incus_cert_confirmed_toast", "incus_cert_repinned_toast",
            "incus_identity_rotated_toast", "incus_cert_mismatch"));

    static final List<TrustLane> TRUST_LANES = List.of(INCUS_LANE, SSH_LANE);

    private ServerTrustActions() {
    }

    /** The four ceremony actions of every lane, in lane order. */
    static @NonNull List<RowAction<Row>> actions() {
        List<RowAction<Row>> actions = new ArrayList<>();
        for (TrustLane lane : TRUST_LANES) {
            actions.add(scanAction(lane));
            actions.add(confirmAction(lane));
            actions.add(repinAction(lane));
            actions.add(rotateAction(lane));
        }
        return actions;
    }

    /**
     * Mint ONE lane's client credential when the record declares that lane and has none.
     *
     * @throws Violations {@code identity_generation_failed} when the key tool refused
     */
    static void ensureLaneIdentity(@NonNull Row server, @NonNull TrustLane lane) {
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
     * Ask the host which identity it offers on this lane and pin it if there is nothing
     * to contradict. A DIFFERENT one never re-pins here: it is stored as evidence and the
     * host is quarantined, because "reconnect and it healed itself" is the exact behaviour
     * a man-in-the-middle needs.
     */
    private static @NonNull RowAction<Row> scanAction(@NonNull TrustLane lane) {
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
    private static @NonNull RowAction<Row> confirmAction(@NonNull TrustLane lane) {
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
    private static @NonNull RowAction<Row> repinAction(@NonNull TrustLane lane) {
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
    private static @NonNull RowAction<Row> rotateAction(@NonNull TrustLane lane) {
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
}
