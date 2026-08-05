package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * An instance template (the Pterodactyl "egg" analogue): an operator-curated preset of
 * kind + settings, a TYPED variable schema (instance_template_variables), config files
 * (instance_template_files), an optional install step and a declared reinstall data
 * policy. Templates are the DEFAULT source of instance images -- a tenant creates from
 * an APPROVED template; an arbitrary image needs the {@code image_any} capability
 * (InstanceImagePolicy).
 *
 * AIDEV-NOTE: there is deliberately NO separate port_requirements structure. The kind
 * settings' own {@code container_port} + {@code port_protocol} + {@code port_exposure}
 * + {@code host_port} ARE the port requirement (they grew WITH the pre-allocation
 * enforcement, as this note always demanded). A multi-port LIST still does not exist
 * because the runtime honors exactly one publication per instance; grow the list only
 * together with the runtime that enforces it.
 *
 * AIDEV-NOTE: {@code readiness_line} and {@code stop_command} are DECLARED matcher data
 * for the Phase 6 console wave (log-stream readiness detection, graceful stop via
 * console command). They are carried, edited and exported here so that wave only wires
 * them; nothing streams or matches yet -- the driver seam is single-shot by contract.
 */
public class InstanceTemplateModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_template");
    public static final Schema SCHEMA = new Schema();

    /** {@link #REINSTALL_POLICY}: a reinstall keeps the instance's volumes and their data. */
    public static final String REINSTALL_PRESERVE = "preserve";

    /** {@link #REINSTALL_POLICY}: a reinstall wipes the volumes before the install step. */
    public static final String REINSTALL_CLEAR = "clear";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    // Catalog data authored by an operator (or imported): travels between installs in
    // the export format, so it is a plain string, never localized content.
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimFormCopy.label("name"))
        .build());

    public static final TextField DESCRIPTION = SCHEMA.addField(TextField.builder().name("description")
        .label(HohenheimFormCopy.label("description"))
        .help(HohenheimFormCopy.help("template_description"))
        .build());

    // Same ONE discriminator as InstanceModel: the kind implies the runtime.
    public static final EnumField KIND = SCHEMA.addField(
        RegistryEnumField.builder("kind")
            .registry(InstanceKindRegistry.REGISTRY)
            .label(HohenheimFormCopy.label("kind"))
            .help(HohenheimFormCopy.help("template_kind"))
            .build());

    // The settings BASELINE a created instance starts from (image, tag, command,
    // container_port, env, volumes, limits for docker_container). The image inside this
    // map is what template approval approves.
    public static final SchemaField SETTINGS = SCHEMA.addField(
        SchemaField.builder("settings")
            .schemaFrom("kind")
            .label(HohenheimFormCopy.label("settings"))
            .build());

    /** Operator-bumped catalog version, carried through export/import. */
    public static final IntegerField VERSION = SCHEMA.addField(
        IntegerField.builder().name("version")
            .defaultValue(1)
            .label(HohenheimFormCopy.label("template_version"))
            .help(HohenheimFormCopy.help("template_version"))
            .build());

    /** Image the optional install step runs in (empty = no install step). */
    public static final StringField INSTALL_IMAGE = SCHEMA.addField(
        StringField.builder().name("install_image")
            .label(HohenheimFormCopy.label("install_image"))
            .help(HohenheimFormCopy.help("install_image"))
            .build());

    /** Shell script of the install step, run with the instance's volumes and variables. */
    public static final TextField INSTALL_SCRIPT = SCHEMA.addField(
        TextField.builder().name("install_script")
            .label(HohenheimFormCopy.label("install_script"))
            .help(HohenheimFormCopy.help("install_script"))
            .build());

    /**
     * In-place app update script (the community-scripts {@code update_script()}
     * capability): runs INSIDE the running instance through the same function-library
     * lane as the install script; empty = the template declares no update path.
     */
    public static final TextField UPDATE_SCRIPT = SCHEMA.addField(
        TextField.builder().name("update_script")
            .label(HohenheimFormCopy.label("update_script"))
            .help(HohenheimFormCopy.help("update_script"))
            .build());

    /** The EXPLICIT reinstall data policy (the plan's requirement: never an implicit wipe). */
    public static final EnumField REINSTALL_POLICY = SCHEMA.addField(EnumField.builder("reinstall_policy")
        .value(REINSTALL_PRESERVE, v -> v.displayName("Preserve data").icon("shield")
            .label(Microcopy.of("preserve").withFilter("scope", "reinstall_policy")).color("green"))
        .value(REINSTALL_CLEAR, v -> v.displayName("Clear data").icon("eraser")
            .label(Microcopy.of("clear").withFilter("scope", "reinstall_policy")).color("red"))
        .defaultValue(REINSTALL_PRESERVE)
        .label(HohenheimFormCopy.label("reinstall_policy"))
        .help(HohenheimFormCopy.help("reinstall_policy"))
        .build());

    /** Console line marking readiness (Phase 6 matcher data; carried, not yet wired). */
    public static final StringField READINESS_LINE = SCHEMA.addField(
        StringField.builder().name("readiness_line")
            .label(HohenheimFormCopy.label("readiness_line"))
            .help(HohenheimFormCopy.help("readiness_line"))
            .build());

    /** Console command for a graceful stop (Phase 6 matcher data; carried, not yet wired). */
    public static final StringField STOP_COMMAND = SCHEMA.addField(
        StringField.builder().name("stop_command")
            .label(HohenheimFormCopy.label("stop_command"))
            .help(HohenheimFormCopy.help("stop_command"))
            .build());

    /**
     * When an operator approved this template for tenant selection; null = NOT
     * tenant-selectable. Every template starts unapproved -- authored AND imported --
     * so approval is ONE uniform gate instead of an origin-dependent special case.
     */
    public static final DateTimeField APPROVED_AT = SCHEMA.addField(
        DateTimeField.builder().name("approved_at")
            .label(HohenheimFormCopy.label("approved_at"))
            .build());

    /** The approving operator's principal id (accountability beside the timestamp). */
    public static final LongField APPROVED_BY_USER_ID = SCHEMA.addField(
        LongField.builder("approved_by_user_id").filterable(false).build());

    /** Where an imported template came from (operator-supplied origin note); null = authored here. */
    public static final StringField SOURCE = SCHEMA.addField(
        StringField.builder().name("source")
            .label(HohenheimFormCopy.label("template_source"))
            .build());

    /** sha256 of the imported payload's canonical template body (verified at import). */
    public static final StringField SOURCE_CHECKSUM = SCHEMA.addField(
        StringField.builder().name("source_checksum").filterable(false).build());

    public static final DateTimeField IMPORTED_AT = SCHEMA.addField(
        DateTimeField.builder().name("imported_at").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(NAME);
    }

    /** Every template, name order (catalog listings). */
    public List<Row> findAll() {
        return find().orderBy(NAME, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "InstanceTemplate"; }

    @Override
    public String getTableName() { return "instance_templates"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
