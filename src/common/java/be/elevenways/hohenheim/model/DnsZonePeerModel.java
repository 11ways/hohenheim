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
