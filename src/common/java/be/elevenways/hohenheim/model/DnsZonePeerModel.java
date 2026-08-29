package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;

import java.util.List;

/**
 * Links a primary zone to a peer that secondaries it: the zone's NOTIFY targets
 * and the set of TSIG keys authorized to pull it over AXFR.
 */
public class DnsZonePeerModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "dns_zone_peer");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField ZONE_ID = SCHEMA.addField(IntegerField.builder().name("zone_id").build());
    public static final IntegerField PEER_ID = SCHEMA.addField(IntegerField.builder().name("peer_id").build());

    // --- Freshness of the secondary, as probed from THIS primary (DnsSecondaryFreshness) ---
    /** The SOA serial the peer answered with at the last probe; null when it never answered. */
    public static final IntegerField SERVED_SERIAL = SCHEMA.addField(
        IntegerField.builder().name("served_serial").build());
    public static final DateTimeField PROBED_AT = SCHEMA.addField(
        DateTimeField.builder().name("probed_at").build());
    /** Why the last probe got no usable answer; null when the peer answered. */
    public static final StringField PROBE_ERROR = SCHEMA.addField(
        StringField.builder().name("probe_error").build());
    /** First probe at which the peer was behind or silent; null while it serves our serial. */
    public static final DateTimeField BEHIND_SINCE = SCHEMA.addField(
        DateTimeField.builder().name("behind_since").build());
    /** When the stale alert for the current lag went out; null once the peer caught up. */
    public static final DateTimeField STALE_ALERTED_AT = SCHEMA.addField(
        DateTimeField.builder().name("stale_alerted_at").build());

    // --- What THIS primary last did for the peer (DnsFederationTrace) ---
    /** When this primary last streamed the zone to the peer's TSIG key over AXFR. */
    public static final DateTimeField LAST_AXFR_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_axfr_at").build());
    /** The serial that last served AXFR carried. */
    public static final IntegerField LAST_AXFR_SERIAL = SCHEMA.addField(
        IntegerField.builder().name("last_axfr_serial").build());
    /** When this primary last sent the peer a NOTIFY for the zone. */
    public static final DateTimeField LAST_NOTIFY_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_notify_at").build());
    /** The serial that NOTIFY announced: the one the serving view published, not a re-read. */
    public static final IntegerField LAST_NOTIFY_SERIAL = SCHEMA.addField(
        IntegerField.builder().name("last_notify_serial").build());
    /** What came back for that NOTIFY: the ack's rcode, a timeout, or the send error. */
    public static final StringField LAST_NOTIFY_OUTCOME = SCHEMA.addField(
        StringField.builder().name("last_notify_outcome").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /** The linked zone; a link dies with its zone (the delete cascade asks through this). */
    public static final BelongsTo<DnsZoneModel> ZONE = SCHEMA.addRelation(
        BelongsTo.to(DnsZoneModel.class)
            .name("zone")
            .localKey(ZONE_ID)
            .remoteKey(DnsZoneModel.ID)
            .build());

    /** The linked peer; a link dies with its peer (the delete cascade asks through this). */
    public static final BelongsTo<DnsPeerModel> PEER = SCHEMA.addRelation(
        BelongsTo.to(DnsPeerModel.class)
            .name("peer")
            .localKey(PEER_ID)
            .remoteKey(DnsPeerModel.ID)
            .build());

    public List<Row> findByZoneId(int zoneId) {
        return find().where(ZONE_ID.eq(zoneId)).all();
    }

    public List<Row> findByPeerId(int peerId) {
        return find().where(PEER_ID.eq(peerId)).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "DnsZonePeer"; }

    @Override
    public String getTableName() { return "dns_zone_peers"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
