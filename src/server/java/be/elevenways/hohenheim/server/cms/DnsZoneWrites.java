package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A DNS zone created or changed by its settings as an assistant names them, through the zone panel resource's own
 * create and save, and its secondaries made exactly the list given through the zone-peer resource's own links.
 *
 * AIDEV-NOTE: the entries are the zone form's own fields (DnsZoneParts.formSpec) under their input names, the primary
 * peer and the secondaries taken by their codes. Only the operator's panel declares the zone resources, so a caller
 * who is no administrator is refused: by PanelRecordWrites for the zone, by the zone-peer resource's own permission
 * for the secondaries.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class DnsZoneWrites {

    /** The argument naming the peer a secondary zone is copied from. */
    public static final String PRIMARY_PEER = DnsZoneModel.PRIMARY_PEER_ID.getInputName();

    /** The argument listing the peers that keep a copy of a primary zone. */
    public static final String SECONDARIES = "secondaries";

    /** The zone's own settings, as its form names them. */
    private static final List<Field<?, ?>> SETTINGS = List.of(DnsZoneModel.ORIGIN, DnsZoneModel.ENABLED,
        DnsZoneModel.ROLE, DnsZoneModel.SOA_PRIMARY_NS, DnsZoneModel.SOA_CONTACT, DnsZoneModel.DEFAULT_TTL,
        DnsZoneModel.NEGATIVE_TTL, DnsZoneModel.SOA_REFRESH, DnsZoneModel.SOA_RETRY, DnsZoneModel.SOA_EXPIRE,
        DnsZoneModel.DNSSEC_ENABLED);

    private static final PanelRecordWrites WRITES = PanelRecordWrites.of("dns_zone", DnsZoneModel.MODEL_ID,
            DnsOperations.ZONE, DnsZoneModel.ID, HohenheimSlugs.DNS_ZONES)
        .settings(SETTINGS)
        .link(PRIMARY_PEER, HohenheimMicrocopy.HELP.of("zone_primary_peer"), DnsZoneModel.PRIMARY_PEER_ID,
            DnsPeerModel.MODEL_ID, false)
        .also(List.of(OperationForms.recordIds(SECONDARIES, HohenheimMicrocopy.HELP.of("zone_secondaries"),
            DnsPeerModel.MODEL_ID)), DnsZoneWrites::secondariesChanged)
        .alsoOnCreate(DnsZoneWrites::secondariesCreated)
        .build();

    /** Creates a zone, its secondaries with it. */
    public static final Operation<Void, OperationInput.Values, Integer> CREATE = WRITES.create();

    /** Changes what the call sends of a zone; every other setting stays as it is. */
    public static final Operation<Row, OperationInput.Values, String> CHANGE = WRITES.change();

    private DnsZoneWrites() {
    }

    /** Installs the handlers; idempotent through the class initializer. */
    public static void init() {
    }

    /** Refuses secondaries for a zone created as a secondary itself before it is written, then links them. */
    private static @NonNull Consumer<Integer> secondariesCreated(OperationInput.@NonNull Values input,
                                                                 @NonNull Set<String> sent,
                                                                 @NonNull AccessContext caller) {
        List<?> peers = peers(input);
        refuseOnSecondary(input.get(DnsZoneModel.ROLE.getInputName()), peers);
        return zoneId -> linkSecondaries(zoneId, peers, caller);
    }

    /** Links the secondaries once the zone's settings are written, so a role changed with them is the one judged. */
    private static @NonNull Runnable secondariesChanged(@NonNull Row zone, OperationInput.@NonNull Values input,
                                                        @NonNull Set<String> sent, @NonNull AccessContext caller) {
        List<?> peers = peers(input);
        Integer zoneId = Objects.requireNonNull(zone.get(DnsZoneModel.ID));
        return () -> linkSecondaries(zoneId, peers, caller);
    }

    /** @return the secondaries the call lists, none for an empty or cleared list */
    private static @NonNull List<?> peers(OperationInput.@NonNull Values input) {
        return input.get(SECONDARIES) instanceof List<?> list ? list : List.of();
    }

    /** @throws Violations on the secondaries argument for a zone that is itself a secondary and is given some */
    private static void refuseOnSecondary(@Nullable Object role, @NonNull List<?> peers) {
        if (!peers.isEmpty() && DnsZoneModel.ROLE_SECONDARY.equals(role)) {
            throw Violations.ofField(SECONDARIES, peers, HohenheimMicrocopy.VIOLATIONS.of("dns_secondaries_primary"));
        }
    }

    /**
     * Makes the zone's secondaries exactly the peers given: a peer no longer listed loses its link, a newly listed one
     * gets one through the operator's zone-peer resource, which tells it to pull at once.
     */
    private static void linkSecondaries(@NonNull Integer zoneId, @NonNull List<?> peers,
                                        @NonNull AccessContext caller) {
        Row zone = Objects.requireNonNull(Models.get(DnsZoneModel.class).findById(zoneId), "the written zone");
        refuseOnSecondary(DnsZoneModel.roleOf(zone), peers);
        Set<Integer> wanted = new LinkedHashSet<>();
        for (Object peer : peers) wanted.add(Integer.valueOf(String.valueOf(peer)));
        Panel panel = ApiConduits.adminPanel();
        PanelResource<Row> links = Objects.requireNonNull(CmsSupport.declaredRowEntry(panel,
            HohenheimSlugs.DNS_ZONE_PEERS), "the panel declares the zone secondaries");
        List<Integer> linked = new ArrayList<>();
        for (Row link : Models.get(DnsZonePeerModel.class).find().where(DnsZonePeerModel.ZONE_ID.eq(zoneId)).all()) {
            Integer peer = link.get(DnsZonePeerModel.PEER_ID);
            if (wanted.contains(peer)) {
                linked.add(peer);
            } else {
                ResourceWrites.delete(panel, links, link, caller);
            }
        }
        for (Integer peer : wanted) {
            if (linked.contains(peer)) continue;
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put(DnsZonePeerModel.ZONE_ID.getInputName(), String.valueOf(zoneId));
            raw.put(DnsZonePeerModel.PEER_ID.getInputName(), String.valueOf(peer));
            KindSettingsWrites.speaking(Map.of(SECONDARIES, DnsZonePeerModel.PEER_ID),
                () -> ResourceWrites.create(panel, links, raw, caller));
        }
    }
}
