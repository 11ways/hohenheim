package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.wording.Noun;
import be.elevenways.protoblast.common.i18n.wording.NounFact;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.ui.ColorHue;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * One redirect inside one site: a request for {@link #FROM_PATH} (exactly, or anything under it) is answered with a
 * redirect to {@link #TARGET} before the site's own handler or gates run, whatever kind of site it is.
 *
 * AIDEV-NOTE: like a protected path, deliberately NOT a site_domains row: it claims no traffic, so it never enters
 * route identity, claims or quarantine. The path is matched against the request path as the visitor sent it.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class SiteRedirectModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("site_redirect");
    /** The site redirect model's reference: its class and MODEL_ID, resolved at first use. */
    public static final ModelRef<SiteRedirectModel> REF = ModelRef.of(SiteRedirectModel.class, MODEL_ID);

    /** The path matches only itself. */
    public static final String MATCH_EXACT = "exact";

    /** The path matches itself and everything under it, and the rest of the request path follows the target. */
    public static final String MATCH_PREFIX = "prefix";

    /** The status a redirect answers unless it names one: a moved page is moved for good. */
    public static final String DEFAULT_STATUS = "301";

    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("site")).build());
    public static final StringField FROM_PATH = SCHEMA.addField(StringField.builder().name("from_path")
        .label(HohenheimMicrocopy.SITE_REDIRECT.of("from_path"))
        .help(HohenheimMicrocopy.HELP.of("redirect_from_path"))
        .placeholder("/old-page")
        .build());
    public static final EnumField MATCH = SCHEMA.addField(EnumField.builder("match_kind")
        .value(MATCH_EXACT, v -> v.displayName("Exact")
            .label(HohenheimMicrocopy.SITE_REDIRECT.of("match_exact")).icon("file").color(ColorHue.GREEN))
        .value(MATCH_PREFIX, v -> v.displayName("Prefix")
            .label(HohenheimMicrocopy.SITE_REDIRECT.of("match_prefix")).icon("folder-tree").color(ColorHue.BLUE))
        .defaultValue(MATCH_EXACT)
        .label(HohenheimMicrocopy.SITE_REDIRECT.of("match"))
        .help(HohenheimMicrocopy.HELP.of("redirect_match"))
        .build());
    public static final StringField TARGET = SCHEMA.addField(StringField.builder().name("target")
        .label(HohenheimMicrocopy.SITE_REDIRECT.of("target"))
        .help(HohenheimMicrocopy.HELP.of("redirect_target"))
        .placeholder("/new-page")
        .build());
    public static final EnumField HTTP_STATUS = SCHEMA.addField(RedirectStatuses.field("http_status")
        .defaultValue(DEFAULT_STATUS).build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public static final BelongsTo<SiteModel> SITE = SCHEMA.addRelation(
        BelongsTo.to(SiteModel.class)
            .name("site")
            .localKey(SITE_ID)
            .remoteKey(SiteModel.ID)
            .build());

    static {
        SCHEMA.setDisplayFields(FROM_PATH);
    }

    /** The site's redirects, in path order. */
    public List<Row> findBySiteId(int siteId) {
        return find().where(SITE_ID.eq(siteId)).orderBy(FROM_PATH, SortOrder.ASC).all();
    }

    private static final Noun NOUN = new Noun(Map.of(
        "en", new Noun.Forms("redirect", "redirects", Map.of(NounFact.ARTICLE, "a")),
        "nl", new Noun.Forms("doorverwijzing", "doorverwijzingen", Map.of(NounFact.GENDER, "common"))));

    @Override public @NonNull Noun noun() { return NOUN; }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "SiteRedirect"; }

    @Override
    public String getTableName() { return "site_redirects"; }

    @Override
    public Schema getSchema() { return SCHEMA; }

    /** A redirect's change is filed on its site. */
    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.optional().changes()
        .filedOn(SiteModel.class, row -> row.get(SITE_ID)));
}
