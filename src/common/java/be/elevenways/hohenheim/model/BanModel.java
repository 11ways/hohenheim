package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.security.BanScope;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.orm.query.rules.VariableDefinition;
import be.elevenways.zenit.common.ui.ColorHue;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;

/**
 * An IP ban: auto-created by the threat scorer or the spamservice reputation
 * policy, or manual from the admin. A null expires_at means permanent; the
 * kernel expires nftables elements itself, this row is the source of truth.
 */
public class BanModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("ban");
    public static final Schema SCHEMA = new Schema();

    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_MANUAL = "manual";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField IP = SCHEMA.addField(StringField.builder().name("ip")
        .label(HohenheimFormCopy.label("ip"))
        .build());
    public static final StringField REASON = SCHEMA.addField(StringField.builder().name("reason")
        .label(HohenheimFormCopy.label("ban_reason"))
        .build());
    public static final EnumField SOURCE = SCHEMA.addField(EnumField.builder("source")
        .value(SOURCE_AUTO, v -> v.displayName("Auto")
            .label(Microcopy.of("auto").withFilter("scope", "ban_source"))
            .icon("robot").color(ColorHue.ORANGE))
        .value(SOURCE_MANUAL, v -> v.displayName("Manual")
            .label(Microcopy.of("manual").withFilter("scope", "ban_source"))
            .icon("pen").color(ColorHue.BLUE))
        .label(HohenheimFormCopy.label("ban_source"))
        .build());
    /**
     * WHICH traffic this ban refuses; the vocabulary lives on {@link BanScope}, never as a
     * second list of tokens here.
     */
    public static final EnumField SCOPE = SCHEMA.addField(scopeField());

    /** The scope field, one value per {@link BanScope} member and nothing else. */
    private static EnumField scopeField() {
        EnumField.Builder builder = EnumField.builder("scope");
        for (BanScope scope : BanScope.values()) {
            builder.value(scope.token(), v -> v.displayName(scope.displayName())
                .label(scope.label()).icon(scope.icon()).color(scope.color()));
        }
        return builder.defaultValue(BanScope.WEB.token())
            .label(HohenheimFormCopy.label("ban_scope"))
            .build();
    }
    public static final StringField EVENT_TYPE = SCHEMA.addField(StringField.builder().name("event_type")
        .label(HohenheimFormCopy.label("event_type"))
        .build());
    public static final DateTimeField EXPIRES_AT = SCHEMA.addField(
        DateTimeField.builder().name("expires_at")
            .label(HohenheimFormCopy.label("expires_at"))
            .build());
    public static final BooleanField ACTIVE = SCHEMA.addField(BooleanField.builder("active")
        .defaultValue(true)
        .label(HohenheimFormCopy.label("active"))
        .build());
    public static final DateTimeField LIFTED_AT = SCHEMA.addField(
        DateTimeField.builder().name("lifted_at")
            .label(HohenheimFormCopy.label("lifted_at"))
            .build());
    public static final StringField LIFTED_BY = SCHEMA.addField(
        StringField.builder().name("lifted_by")
            .label(HohenheimFormCopy.label("lifted_by"))
            .build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    static {
        // A ban is an address; the reason is the subtext everywhere it renders.
        SCHEMA.setDisplayFields(IP);
    }

    /** The rule variable of {@link #blockedNow(Instant)}: the list's "Blocked now" filter and the dashboard tile. */
    public static final String BLOCKED_NOW = "blocked_now";

    /**
     * Whether this ban blocks its address NOW: enforced, never lifted, and not past its expiry.
     *
     * AIDEV-NOTE: THE definition the list's filter ({@link #blockedNow(Instant)}), the state cell and Lift's
     * availability read. The stored {@code active} flag alone is not it: the expiry sweep clears it only on its next
     * run, so an expired ban still reads active until then (DEP9: "Blocked now" listed a ban whose state read
     * Expired, with Lift offered).
     *
     * @param now the instant asked about, read through {@code Now} by every caller
     */
    public static boolean blockedNow(@NonNull Row ban, @NonNull Instant now) {
        Instant expires = ban.get(EXPIRES_AT);
        return Boolean.TRUE.equals(ban.get(ACTIVE)) && ban.get(LIFTED_AT) == null
            && (expires == null || expires.isAfter(now));
    }

    /**
     * {@link #blockedNow(Row, Instant)} as a query: two-valued, so its negation keeps the rows with a null column.
     *
     * @param now the instant asked about, read through {@code Now} by every caller
     */
    public static @NonNull Criteria blockedNow(@NonNull Instant now) {
        return Criteria.and(ACTIVE.isNotNull(), ACTIVE.eq(true), LIFTED_AT.isNull(),
            Criteria.or(EXPIRES_AT.isNull(), Criteria.and(EXPIRES_AT.isNotNull(), EXPIRES_AT.gt(now))));
    }

    /** @return the {@link #BLOCKED_NOW} rule variable, compiled against the clock at query time */
    public static @NonNull VariableDefinition blockedNowVariable() {
        return VariableDefinition.bool(BLOCKED_NOW, (call, context) -> call.truth()
                ? blockedNow(Now.instant()) : Criteria.not(blockedNow(Now.instant())))
            .label(Microcopy.of("blocked_now").withFilter("scope", "ban"))
            .build();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Ban"; }

    @Override
    public String getTableName() { return "bans"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
