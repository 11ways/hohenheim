package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * A guarded path prefix inside one site: requests under {@link #PATH} must additionally
 * pass {@link #ACCESS_LIST_ID}'s rule tree, on top of whatever the site itself requires.
 *
 * AIDEV-NOTE: deliberately NOT a site_domains row. A domain row is a ROUTE (it claims
 * traffic and participates in the route-claim ledger); this row is pure AUTHORIZATION over
 * traffic the site's routes already own, so it must never enter route identity, claims or
 * quarantine. It compiles into the same RouteEntry the site's own access list rides.
 */
public class ProtectedPathModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("protected_path");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("site")).build());
    public static final StringField PATH = SCHEMA.addField(StringField.builder().name("path")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("path_prefix"))
        .help(HohenheimMicrocopy.HELP.of("protected_path_prefix"))
        .placeholder("/private")
        .build());
    public static final IntegerField ACCESS_LIST_ID = SCHEMA.addField(IntegerField.builder()
        .name("access_list_id")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("access_list"))
        .help(HohenheimMicrocopy.HELP.of("protected_path_access_list"))
        .build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public static final BelongsTo<SiteModel> SITE = SCHEMA.addRelation(
        BelongsTo.to(SiteModel.class)
            .name("site")
            .localKey(SITE_ID)
            .remoteKey(SiteModel.ID)
            .build());
    public static final BelongsTo<AccessListModel> ACCESS_LIST = SCHEMA.addRelation(
        BelongsTo.to(AccessListModel.class)
            .name("access_list")
            .localKey(ACCESS_LIST_ID)
            .remoteKey(AccessListModel.ID)
            .build());

    static {
        SCHEMA.setDisplayFields(PATH);
    }

    /** The site's guarded prefixes, stable order for the tab and the route compiler. */
    public List<Row> findBySiteId(int siteId) {
        return find().where(SITE_ID.eq(siteId)).orderBy(PATH, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "ProtectedPath"; }

    @Override
    public String getTableName() { return "protected_paths"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
