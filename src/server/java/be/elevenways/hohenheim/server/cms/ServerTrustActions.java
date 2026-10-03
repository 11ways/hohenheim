package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HostTrustLane;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
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

import static be.elevenways.hohenheim.server.cms.ServerWords.serverCopy;

/**
 * The host trust ceremony's one lane table and its scan/confirm/repin/rotate operation placements.
 *
 * AIDEV-NOTE: split out of ServerResource (review, 2026-09). HostEnrolment mints through THIS table too, so no second
 * "which lane applies and how is its credential minted" vocabulary exists. An Incus host may carry both TLS and SSH
 * relationships; neither shares the other's pin or key. The former row action handlers are replaced and deleted.
 *
 * AIDEV-NOTE: a mismatching scan stores evidence and quarantines; it never repins silently. Repin asks the digest of
 * the actually offered material. A typed phrase guards a mis-click, not authority or evidence of comparison.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class ServerTrustActions {
    record TrustLane(@NonNull String id, @NonNull HostTrustSlot slot, @NonNull Predicate<Row> applies,
                     @NonNull Function<Row, HostKeys.ScanResult> scan, @NonNull Consumer<Row> confirm,
                     @NonNull Consumer<Row> repin, @NonNull Consumer<Row> rotate,
                     @NonNull UnaryOperator<String> digest, @NonNull LaneCopy copy) {}

    record LaneCopy(@NonNull String scan, @NonNull String confirm, @NonNull String repin, @NonNull String rotate,
                    @NonNull String pinnedToast, @NonNull String unchangedToast, @NonNull String confirmedToast,
                    @NonNull String repinnedToast, @NonNull String rotatedToast, @NonNull String mismatch) {}

    static final TrustLane SSH_LANE = new TrustLane(HostTrustLane.SSH.key(), HostTrustSlot.SSH,
        ServerModel::hasSshLane, HostKeys::scanAndPin, HostKeys::confirm, HostKeys::repin,
        HostKeys::rotateIdentity, HostKeys::fingerprintOf,
        new LaneCopy("scan_host_key", "confirm_host_key", "repin_host_key", "rotate_identity",
            "host_key_pinned_toast", "host_key_unchanged_toast", "host_key_confirmed_toast",
            "host_key_repinned_toast", "identity_rotated_toast", "host_key_mismatch"));
    static final TrustLane INCUS_LANE = new TrustLane(HostTrustLane.INCUS.key(), HostTrustSlot.INCUS_TLS,
        ServerModel::isIncusHttps, IncusTrust::scanAndPin, IncusTrust::confirm, IncusTrust::repin,
        IncusTrust::rotateIdentity, IncusTrust::fingerprintOf,
        new LaneCopy("scan_incus_cert", "confirm_incus_cert", "repin_incus_cert", "rotate_incus_identity",
            "incus_cert_pinned_toast", "incus_cert_unchanged_toast", "incus_cert_confirmed_toast",
            "incus_cert_repinned_toast", "incus_identity_rotated_toast", "incus_cert_mismatch"));
    static final List<TrustLane> TRUST_LANES = List.of(INCUS_LANE, SSH_LANE);
    private static final List<PanelAction<Row>> PLACED = declarePlaced();
    private ServerTrustActions() {}
    static @NonNull List<PanelAction<Row>> placed() { return PLACED; }

    private static List<PanelAction<Row>> declarePlaced() {
        List<PanelAction<Row>> actions = new ArrayList<>();
        for (TrustLane lane : TRUST_LANES) {
            actions.add(ServerLifecycleActions.place("scan_" + lane.id(), serverCopy(lane.copy().scan()), row -> {
                ensureLaneIdentity(row, lane);
                HostKeys.ScanResult result = lane.scan().apply(row);
                if (result.outcome() == HostKeys.ScanOutcome.MISMATCH) {
                    // AIDEV-NOTE: success refreshes only; a Violations refusal is the loud error half of quarantine.
                    throw Violations.ofForm(CmsSupport.violationText(lane.copy().mismatch())
                        .withArg("name", String.valueOf((Object) row.get(ServerModel.NAME)))
                        .withArg("pinned", String.valueOf(result.previous())).withArg("offered", result.fingerprint()));
                }
                return serverCopy(result.outcome() == HostKeys.ScanOutcome.PINNED
                    ? lane.copy().pinnedToast() : lane.copy().unchangedToast()).withArg("fingerprint", result.fingerprint());
            }, lane.applies()).description(serverCopy(lane.copy().scan() + "_hint"))
                .icon(Icon.of("fingerprint")).inlineInRow(false).build());
            actions.add(ServerLifecycleActions.place("confirm_" + lane.id(), serverCopy(lane.copy().confirm()), row -> {
                lane.confirm().accept(row);
                return serverCopy(lane.copy().confirmedToast()).withArg("name", row.get(ServerModel.NAME));
            }, row -> lane.applies().test(row) && lane.slot().isPinned(row)
                && !Boolean.TRUE.equals(row.get(lane.slot().verified())))
                .description(serverCopy(lane.copy().confirm() + "_hint")).icon(Icon.of("shield-halved"))
                .inlineInRow(false).confirmation(ConfirmationSpec.builder().title(serverCopy(lane.copy().confirm()))
                    .body(serverCopy(lane.copy().confirm() + "_generic")).build())
                .dynamicConfirmation(row -> ConfirmationSpec.builder().title(serverCopy(lane.copy().confirm()))
                    .body(serverCopy(lane.copy().confirm() + "_body").withArg("name", row.get(ServerModel.NAME))
                        .withArg("fingerprint", row.get(lane.slot().fingerprint())))
                    .requireTypedConfirmation(row.get(lane.slot().fingerprint())).build()).build());
            actions.add(ServerLifecycleActions.place("repin_" + lane.id(), serverCopy(lane.copy().repin()), row -> {
                lane.repin().accept(row);
                return serverCopy(lane.copy().repinnedToast()).withArg("fingerprint", row.get(lane.slot().fingerprint()));
            }, row -> lane.applies().test(row) && !lane.slot().offeredOf(row).isBlank())
                .description(serverCopy(lane.copy().repin() + "_hint")).icon(Icon.of("triangle-exclamation"))
                .style(ActionStyle.DESTRUCTIVE).inlineInRow(false).confirmation(ConfirmationSpec.builder()
                    .title(serverCopy(lane.copy().repin())).body(serverCopy(lane.copy().repin() + "_generic"))
                    .style(ActionStyle.DESTRUCTIVE).build())
                .dynamicConfirmation(row -> {
                    String offered = lane.slot().offeredOf(row);
                    ConfirmationSpec.Builder confirmation = ConfirmationSpec.builder()
                        .title(serverCopy(lane.copy().repin())).style(ActionStyle.DESTRUCTIVE);
                    if (offered.isBlank()) return confirmation.body(serverCopy(lane.copy().repin() + "_generic")).build();
                    String fingerprint = lane.digest().apply(offered);
                    return confirmation.body(serverCopy(lane.copy().repin() + "_body")
                        .withArg("name", row.get(ServerModel.NAME)).withArg("pinned", row.get(lane.slot().fingerprint()))
                        .withArg("offered", fingerprint)).requireTypedConfirmation(fingerprint).build();
                }).build());
            actions.add(ServerLifecycleActions.place("rotate_" + lane.id(), serverCopy(lane.copy().rotate()), row -> {
                lane.rotate().accept(row);
                return serverCopy(lane.copy().rotatedToast()).withArg("name", row.get(ServerModel.NAME));
            }, lane.applies()).description(serverCopy(lane.copy().rotate() + "_hint"))
                .icon(Icon.of("key")).style(ActionStyle.DESTRUCTIVE).inlineInRow(false)
                .confirmation(ConfirmationSpec.builder().title(serverCopy(lane.copy().rotate()))
                    .body(serverCopy(lane.copy().rotate() + "_generic")).style(ActionStyle.DESTRUCTIVE).build())
                .dynamicConfirmation(row -> ConfirmationSpec.builder().title(serverCopy(lane.copy().rotate()))
                    .body(serverCopy(lane.copy().rotate() + "_body").withArg("name", row.get(ServerModel.NAME)))
                    .style(ActionStyle.DESTRUCTIVE).requireTypedConfirmation(row.get(ServerModel.NAME)).build()).build());
        }
        return List.copyOf(actions);
    }

    static void ensureLaneIdentity(@NonNull Row server, @NonNull TrustLane lane) {
        if (!lane.applies().test(server)) return;
        String existing = server.get(lane.slot().clientPrivate());
        if (existing == null || existing.isBlank()) lane.rotate().accept(server);
    }
}
