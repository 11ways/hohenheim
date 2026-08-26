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
 * A named, attachable access policy: the list row IS the implicit ROOT group of an
 * {@link AccessRuleModel} tree, and {@link #SATISFY} is that root's any/all mode.
 *
 * AIDEV-NOTE: the flat shape (one basic-auth pair plus two free-text IP columns) was
 * replaced by the tree on 2026-08-19. A list carrying no rules is INERT, not a lockout --
 * the root group is empty and an empty group passes.
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
    public static final EnumField SATISFY = SCHEMA.addField(EnumField.builder("satisfy")
        .value(SATISFY_ANY, v -> v.displayName("Any")
            .label(Microcopy.of("any").withFilter("scope", "access_satisfy"))
            .icon("check").color("blue"))
        .value(SATISFY_ALL, v -> v.displayName("All")
            .label(Microcopy.of("all").withFilter("scope", "access_satisfy"))
            .icon("list-check").color("orange"))
        .defaultValue(SATISFY_ANY)
        .label(HohenheimFormCopy.label("satisfy"))
        .build());
    /**
     * Offered to every principal's pickers, not only to the subjects holding a manage
     * grant on the row -- the operator's declaration that this policy is for general use
     * (the {@link GitProviderModel#SHARED} shape). Default false, so a list a tenant
     * creates in /manage is private to its owners until an admin says otherwise.
     */
    public static final BooleanField SHARED = SCHEMA.addField(BooleanField.builder("shared")
        .defaultValue(false)
        .label(HohenheimFormCopy.label("access_list_shared"))
        .help(HohenheimFormCopy.help("access_list_shared"))
        .build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(NAME);

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
            // Membership derives from the field's own value set, so the vocabulary
            // has exactly one declaring home (the SATISFY EnumField above).
            if (!SATISFY.isValidValue(satisfy)) {
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
