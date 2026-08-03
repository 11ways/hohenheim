package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * THE released-claim ledger: one immutable row per route claim a site GAVE UP, carrying
 * the claim key, its hostname, the former owner's manage-grant subjects and when the
 * release happened. {@code be.elevenways.hohenheim.server.proxy.ReleasedClaims} owns every
 * read and write; nothing else saves into this table.
 *
 * AIDEV-NOTE: a TABLE, not a RouteClaims-style column, because a released claim frequently
 * has no live row left to hang a column on (the domain row may be gone entirely). It
 * mirrors RouteClaims' key DERIVATION (RouteClaims.keyOf is the one authority -- this table
 * never re-derives a key) but deliberately NOT its unique index: the same hostname can be
 * released, re-claimed and released again any number of times, and a unique claim key would
 * turn the second legitimate release into a hard write failure. Exclusivity is not this
 * table's job -- live_route_key still owns that; this table only remembers WHO let go and
 * WHEN, so the quarantine window can refuse a DIFFERENT owner's takeover.
 *
 * AIDEV-NOTE: no timestamps() on purpose. A row is written once and never updated, so an
 * updated_at column could only ever lie; {@link #RELEASED_AT} is the one time this table
 * has an opinion about.
 */
public class ReleasedRouteClaimModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "released_route_claim");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    /** The released route tuple, spelled by {@code RouteClaims.keyOf} -- never re-derived here. */
    public static final StringField CLAIM_KEY = SCHEMA.addField(
        StringField.builder().name("claim_key").filterable(false).build());

    /** The canonical hostname of {@link #CLAIM_KEY}, for the refusal message and the admin list. */
    public static final StringField HOSTNAME = SCHEMA.addField(
        StringField.builder().name("hostname")
            .label(HohenheimFormCopy.label("hostname"))
            .build());

    /** The site that held the claim; kept for the operator's override decision, may be gone. */
    public static final IntegerField FORMER_SITE_ID = SCHEMA.addField(
        IntegerField.builder().name("former_site_id")
            .label(HohenheimFormCopy.label("former_site"))
            .build());

    /**
     * The former owner: the site's {@code manage}-grant subjects, spelled
     * {@code subjectType:subjectId} exactly as {@code HohenheimAccess} spells them,
     * canonically sorted and newline-joined. EMPTY means operator-owned.
     */
    public static final TextField FORMER_SUBJECTS = SCHEMA.addField(
        TextField.builder().name("former_subjects")
            .label(HohenheimFormCopy.label("former_owner"))
            .build());

    /** When the claim was given up; the quarantine window is measured from here. */
    public static final DateTimeField RELEASED_AT = SCHEMA.addField(
        DateTimeField.builder().name("released_at")
            .label(HohenheimFormCopy.label("released_at"))
            .build());

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "ReleasedRouteClaim"; }

    @Override
    public String getTableName() { return "released_route_claims"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
