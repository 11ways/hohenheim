package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.ports.StreamPort;
import be.elevenways.hohenheim.server.upstream.kinds.StreamUpstreamKind;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.site.SitePortsMoved;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Moving a TCP/UDP stream site's public ports into another stream site of the same address, trashing the emptied one
 * in the same transaction.
 *
 * AIDEV-NOTE: the ports' ledger claims are handed to the other site in place (PortLedger.handOver) before the emptied
 * site is trashed and the other saved, so no port is free for a moment and the kernel probe of a new claim never
 * meets the listener still serving it. The proxy's relays are keyed by public port (StreamRelays), so the reload after
 * the commit keeps every moved port's listener and its open connections; only the route new connections take
 * changes, to the other site's (its access list, its connect timeout). The two sites must agree on the address, the
 * access list and the switch, or a moved port would silently forward elsewhere, admit other clients or stop.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SitePortMoves {

    /** The input naming the site the ports move into. */
    public static final String INTO = "into";

    /** Moves the subject's ports into the site {@link #INTO} names; answers what moved, from where into where. */
    public static final Operation<Row, Input, SitePortsMoved> MOVE = Operation
        .declare(HohenheimIds.id("move_site_ports"))
        .happened(OperationSentences.of("move_site_ports"))
        .label(HohenheimMicrocopy.SITE.of("move_ports"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("move_site_ports_description"))
        .icon(Icon.of("compress"))
        .one(SiteOperations.SITE)
        .verb(HohenheimVerbs.MOVE_PORTS)
        .gate(OperationGate.open())
        .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
        .input(OperationInput.of(FormSpec.builder()
                .add(OperationForms.recordChoice(INTO, HohenheimMicrocopy.SITE.of("move_ports_into"),
                    HohenheimMicrocopy.HELP.of("move_ports_into"), SiteModel.MODEL_ID,
                    OptionSource.dynamic(context -> otherStreams(context.record())), true))
                .build(),
            Input.class, values -> new Input(Texts.trimmedOrNull(values.get(INTO)))))
        .result(SitePortsMoved.class)
        .command(CmsCommands.TRANSACTIONAL)
        .register();

    private SitePortMoves() {
    }

    /** The move's input: the full id of the site the ports move into. */
    public record Input(@Nullable String into) {
    }

    /** @return whether the site forwards public ports of its own, the one kind whose ports move */
    static boolean holdsPorts(@NonNull Row site) {
        return StreamUpstreamKind.ID.toString().equals(site.get(SiteModel.UPSTREAM_KIND));
    }

    /**
     * Hands the source's ports and their claims to the target, trashes the emptied source through {@code trash}
     * and saves the target, inside the operation's one transaction.
     *
     * @return what moved, from where into where
     * @throws Violations in plain words on {@link #INTO} when the target is no live stream site other than the
     *         source, or forwards to another address, behind another access list, or is switched the other way
     */
    static @NonNull SitePortsMoved move(@NonNull Row source, @NonNull Input input, @NonNull Consumer<Row> trash) {
        SiteModel sites = Models.get(SiteModel.class);
        Integer targetId = RawValues.parsedInt(input.into());
        Row target = targetId == null ? null : sites.findById(targetId);
        Integer sourceId = Objects.requireNonNull(source.get(SiteModel.ID));
        if (target == null || !holdsPorts(target)) {
            throw refused(input.into(), "site_ports_move_no_stream", source, target);
        }
        if (sourceId.equals(targetId)) {
            throw refused(input.into(), "site_ports_move_itself", source, target);
        }
        Map<String, Object> from = RawValues.map(source.get(SiteModel.SETTINGS));
        Map<String, Object> into = RawValues.map(target.get(SiteModel.SETTINGS));
        if (!sameAddress(from, into)) {
            throw refused(input.into(), "site_ports_move_other_address", source, target);
        }
        if (!Objects.equals(source.get(SiteModel.ACCESS_LIST_ID), target.get(SiteModel.ACCESS_LIST_ID))) {
            throw refused(input.into(), "site_ports_move_other_access", source, target);
        }
        if (!Objects.equals(source.get(SiteModel.ENABLED), target.get(SiteModel.ENABLED))) {
            throw refused(input.into(), "site_ports_move_other_switch", source, target);
        }
        // Read strictly: an unreadable side refuses the move before anything is written, never drops ports.
        List<StreamPort> moving = StreamPort.of(from);
        Map<String, String> merged = new LinkedHashMap<>(StreamPort.toSettings(StreamPort.of(into)));
        for (StreamPort port : moving) {
            merged.put(String.valueOf(port.listenPort()), port.spec());
        }
        PortLedger.handOver(SiteModel.MODEL_ID, sourceId, Objects.requireNonNull(targetId));
        trash.accept(source);
        Map<String, Object> settings = new LinkedHashMap<>(into);
        settings.put(StreamUpstreamKind.PORTS.getName(), merged);
        target.set(SiteModel.SETTINGS, settings);
        sites.saveWrittenRefusingGone(target);
        return new SitePortsMoved(sourceId, targetId, moving.stream().map(StreamPort::label).toList());
    }

    /** @return the sentence a move answers: the ports moved, the site they left for the trash, the one holding them */
    public static @NonNull Microcopy said(@NonNull SitePortsMoved moved) {
        return HohenheimMicrocopy.SITE.of("ports_moved").withArg("from", codeOf(moved.from()))
            .withArg("into", codeOf(moved.into())).withArg("ports", String.join(", ", moved.ports()))
            .withArg("count", moved.ports().size());
    }

    /** @return the site's code as the tools show it, its id where it has none */
    private static @NonNull String codeOf(int siteId) {
        String code = RecordCodes.codeUnjudged(SiteModel.MODEL_ID, String.valueOf(siteId));
        return code == null ? String.valueOf(siteId) : code;
    }

    /** @return whether both sites forward to the same address, spelled alike but for case and spaces */
    private static boolean sameAddress(@NonNull Map<String, Object> from, @NonNull Map<String, Object> into) {
        String a = Texts.trimmedOrNull(from.get(StreamUpstreamKind.FORWARD_HOST.getName()));
        String b = Texts.trimmedOrNull(into.get(StreamUpstreamKind.FORWARD_HOST.getName()));
        return a != null && a.equalsIgnoreCase(b);
    }

    private static @NonNull Violations refused(@Nullable String given, @NonNull String key, @NonNull Row source,
                                               @Nullable Row target) {
        Map<String, Object> into = target == null ? Map.of() : RawValues.map(target.get(SiteModel.SETTINGS));
        Microcopy words = HohenheimMicrocopy.VIOLATIONS.of(key)
            .withArg("name", String.valueOf((Object) source.get(SiteModel.NAME)))
            .withArg("target", target == null ? String.valueOf(given) : String.valueOf((Object) target.get(
                SiteModel.NAME)))
            .withArg("address", String.valueOf(into.get(StreamUpstreamKind.FORWARD_HOST.getName())));
        return Violations.ofField(INTO, given, words);
    }

    /**
     * The stream sites a move may go into: every live one but the site the form moves from, each named with its
     * ports, so a person picks by what it forwards.
     */
    private static @NonNull List<FieldOption<String>> otherStreams(@Nullable Object subject) {
        Integer self = subject instanceof Row row ? row.get(SiteModel.ID) : null;
        List<FieldOption<String>> options = new ArrayList<>();
        for (Row site : Models.get(SiteModel.class).find()
                .where(SiteModel.UPSTREAM_KIND.eq(StreamUpstreamKind.ID.toString()))
                .orderBy(SiteModel.NAME, SortOrder.ASC).all()) {
            Integer id = site.get(SiteModel.ID);
            if (id == null || id.equals(self)) continue;
            String ports = StreamUpstreamKind.labels(StreamUpstreamKind.portsOf(RawValues.map(
                site.get(SiteModel.SETTINGS))));
            options.add(FieldOption.of(String.valueOf(id), Microcopy.literal(site.get(SiteModel.NAME)
                + (ports.isEmpty() ? "" : " (" + ports + ")"))));
        }
        return options;
    }
}
