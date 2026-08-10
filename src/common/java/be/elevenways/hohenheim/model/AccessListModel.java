package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.validation.Violations;

/**
 * IP allow/deny rules with optional basic auth.
 */
public class AccessListModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "access_list");
    public static final Schema SCHEMA = new Schema();

    /** {@link #SATISFY} value: any matching rule grants access. */
    public static final String SATISFY_ANY = "any";

    /** {@link #SATISFY} value: every configured rule must pass. */
    public static final String SATISFY_ALL = "all";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final StringField SATISFY = SCHEMA.addField(StringField.builder().name("satisfy")
        .defaultValue(SATISFY_ANY)
        .build());
    public static final StringField BASIC_AUTH_USER = SCHEMA.addField(StringField.builder().name("basic_auth_user")
        .label(HohenheimFormCopy.label("basic_auth_user"))
        .help(HohenheimFormCopy.help("basic_auth_user"))
        .build());
    public static final StringField BASIC_AUTH_PASS = SCHEMA.addField(StringField.builder().name("basic_auth_pass")
        .label(HohenheimFormCopy.label("basic_auth_password"))
        .help(HohenheimFormCopy.help("basic_auth_password"))
        .secret()
        .build());
    public static final TextField ALLOWED_IPS = SCHEMA.addField(TextField.builder().name("allowed_ips")
        .label(HohenheimFormCopy.label("allowed_ips"))
        .help(HohenheimFormCopy.help("allowed_ips"))
        .build());
    public static final TextField DENIED_IPS = SCHEMA.addField(TextField.builder().name("denied_ips")
        .label(HohenheimFormCopy.label("denied_ips"))
        .help(HohenheimFormCopy.help("denied_ips"))
        .build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        // AIDEV-NOTE: enforcement lives on the model pipeline, never on a form spec.
        // A staged null/blank satisfy is folded to the default rather than stored, so
        // the column can only ever hold a vocabulary value; the dispatcher additionally
        // defaults a null it still reads (pre-hook rows) at the RouteEntry boundary.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(SATISFY.getName())) return;
            String satisfy = row.get(SATISFY);
            if (satisfy == null || satisfy.isBlank()) {
                row.set(SATISFY, SATISFY_ANY);
                return;
            }
            if (!SATISFY_ANY.equals(satisfy) && !SATISFY_ALL.equals(satisfy)) {
                throw Violations.ofField(SATISFY.getName(), satisfy,
                    Microcopy.of("access_satisfy_invalid").withFilter("scope", "violations"));
            }
        });
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "AccessList"; }

    @Override
    public String getTableName() { return "access_lists"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
