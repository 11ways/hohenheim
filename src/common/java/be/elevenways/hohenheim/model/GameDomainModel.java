package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * A game-domains mapping: one domain record aimed at one backend instance through one
 * Velocity proxy instance, MATERIALIZED as generated forced-hosts config on the proxy
 * and generated SRV/A rows in the DNS tier.
 *
 * AIDEV-NOTE: deliberately NO capability vocabulary of its own (the SiteDomainModel
 * precedent): authority over a mapping is fully derived from its two sides -- manage on
 * the domain's parent site AND manage on both instances -- enforced in GameDomains, the
 * one write funnel. A second grant surface here would be a second, disagreeing owner.
 */
public class GameDomainModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "game_domain");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField SITE_DOMAIN_ID = SCHEMA.addField(
        IntegerField.builder().name("site_domain_id").required()
            .label(HohenheimFormCopy.label("game_domain"))
            .help(HohenheimFormCopy.help("game_domain")).build());

    public static final IntegerField BACKEND_INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("backend_instance_id").required()
            .label(HohenheimFormCopy.label("game_backend"))
            .help(HohenheimFormCopy.help("game_backend")).build());

    public static final IntegerField PROXY_INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("proxy_instance_id").required()
            .label(HohenheimFormCopy.label("game_proxy"))
            .help(HohenheimFormCopy.help("game_proxy")).build());

    public static final IntegerField BACKEND_PORT = SCHEMA.addField(
        IntegerField.builder().name("backend_port").defaultValue(25565)
            .label(HohenheimFormCopy.label("game_backend_port"))
            .help(HohenheimFormCopy.help("game_backend_port")).build());

    public static final BooleanField ENABLED = SCHEMA.addField(
        BooleanField.builder("enabled").defaultValue(true)
            .label(HohenheimFormCopy.label("enabled")).build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    public static final BelongsTo<SiteDomainModel> DOMAIN = SCHEMA.addRelation(
        BelongsTo.to(SiteDomainModel.class).name("domain")
            .localKey(SITE_DOMAIN_ID).remoteKey(SiteDomainModel.ID).build());

    public static final BelongsTo<InstanceModel> BACKEND = SCHEMA.addRelation(
        BelongsTo.to(InstanceModel.class).name("backend")
            .localKey(BACKEND_INSTANCE_ID).remoteKey(InstanceModel.ID).build());

    public static final BelongsTo<InstanceModel> PROXY = SCHEMA.addRelation(
        BelongsTo.to(InstanceModel.class).name("proxy")
            .localKey(PROXY_INSTANCE_ID).remoteKey(InstanceModel.ID).build());

    /** All mappings routed through one proxy instance, stable order. */
    public List<Row> findByProxyId(int proxyInstanceId) {
        return find().where(PROXY_INSTANCE_ID.eq(proxyInstanceId))
            .orderBy(ID, SortOrder.ASC).all();
    }

    /** All mappings whose backend OR proxy is the given instance. */
    public List<Row> findByInstanceId(int instanceId) {
        return find().where(BACKEND_INSTANCE_ID.eq(instanceId))
            .or(PROXY_INSTANCE_ID.eq(instanceId))
            .orderBy(ID, SortOrder.ASC).all();
    }

    /** All mappings bound to one domain record. */
    public List<Row> findBySiteDomainId(int siteDomainId) {
        return find().where(SITE_DOMAIN_ID.eq(siteDomainId))
            .orderBy(ID, SortOrder.ASC).all();
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "GameDomain"; }
    @Override public String getTableName() { return "game_domains"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
