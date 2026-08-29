package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.instance.ConsoleKind;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.instance.StopKind;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;

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
 * AIDEV-NOTE: {@code readiness_line} is only read when {@link #READINESS_KIND} says
 * {@code console_line} -- the other two kinds probe a port after start. That is why the
 * static block REFUSES a line beside a kind that ignores it: the column default is
 * {@code port}, so a line written on an undeclared template used to be dead data and the
 * workload was stamped running the instant its container came up (2026-08-22 regression).
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
     * The runtime image ("yolk") this template layers its hooks over, or null when the
     * template's own {@code settings} name the image.
     *
     * AIDEV-NOTE: this is what stops every Node 22 image appearing twice (once per
     * template kind) -- the vocabulary-duplication objection in section 3 of the phase-0
     * design. A template says "node-22 plus these hooks"; it does not re-declare node-22.
     */
    public static final IntegerField RUNTIME_IMAGE_ID = SCHEMA.addField(
        IntegerField.builder().name("runtime_image_id")
            .label(HohenheimFormCopy.label("runtime_image"))
            .help(HohenheimFormCopy.help("template_runtime_image"))
            .build());

    /** The layered runtime image, declared so its delete can refuse while templates name it. */
    public static final BelongsTo<RuntimeImageModel> RUNTIME_IMAGE = SCHEMA.addRelation(
        BelongsTo.to(RuntimeImageModel.class)
            .name("runtime_image")
            .localKey(RUNTIME_IMAGE_ID)
            .remoteKey(RuntimeImageModel.ID)
            .build());

    /** Overrides the runtime image's default command; {@code \{$VAR\}} expanded at start. */
    public static final StringField START_COMMAND = SCHEMA.addField(
        StringField.builder().name("start_command")
            .label(HohenheimFormCopy.label("start_command"))
            .help(HohenheimFormCopy.help("start_command"))
            .build());

    public static final EnumField READINESS_KIND = SCHEMA.addField(
        ReadinessKind.fieldBuilder("readiness_kind")
            .label(HohenheimFormCopy.label("readiness_kind"))
            .help(HohenheimFormCopy.help("readiness_kind"))
            .build());

    /** The http path or port name {@link #READINESS_KIND} probes; unused by console_line. */
    public static final StringField READINESS_TARGET = SCHEMA.addField(
        StringField.builder().name("readiness_target")
            .label(HohenheimFormCopy.label("readiness_target"))
            .help(HohenheimFormCopy.help("readiness_target"))
            .build());

    public static final EnumField STOP_KIND = SCHEMA.addField(
        StopKind.fieldBuilder("stop_kind")
            .label(HohenheimFormCopy.label("stop_kind"))
            .help(HohenheimFormCopy.help("stop_kind"))
            .build());

    public static final IntegerField STOP_GRACE_SECONDS = SCHEMA.addField(
        IntegerField.builder().name("stop_grace_seconds").defaultValue(10).suffix("s")
            .label(HohenheimFormCopy.label("stop_grace_seconds"))
            .help(HohenheimFormCopy.help("stop_grace_seconds"))
            .build());

    public static final EnumField CONSOLE_KIND = SCHEMA.addField(
        ConsoleKind.fieldBuilder("console_kind")
            .label(HohenheimFormCopy.label("console_kind"))
            .help(HohenheimFormCopy.help("console_kind"))
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

        // THE readiness-declaration invariant. A readiness_line is matcher data for ONE
        // kind; stored beside any other it is silently never read, and the operator who
        // wrote "the log line that means ready" gets a workload stamped running before it
        // is. Refusing here is the only reading that cannot be missed -- the column
        // default is `port`, so silence is what an undeclared template gets for free.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) return;
            Object line = effective(row, READINESS_LINE);
            if (line == null || line.toString().trim().isEmpty()) return;
            Object kind = effective(row, READINESS_KIND);
            if (ReadinessKind.forToken(kind == null ? null : kind.toString())
                    != ReadinessKind.CONSOLE_LINE) {
                throw Violations.ofField(READINESS_KIND.getName(), kind,
                    Microcopy.of("readiness_line_needs_console_line")
                        .withFilter("scope", "violations"));
            }
        });
    }

    /**
     * A field's value AFTER this write lands: an inline cell edit submits only the column
     * it touched, so reading the row alone would judge a partial update against nulls.
     */
    private static Object effective(Row row, Field<?, ?> field) {
        if (row.has(field.getName())) return row.get(field.getName());
        if (!row.has(ID.getName())) return null;
        Row stored = Models.get(InstanceTemplateModel.class).findById(row.get(ID));
        return stored != null ? stored.get(field.getName()) : null;
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
