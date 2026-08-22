package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * The "yolk": WHAT a workspace or application runs inside, split out of the template
 * ("egg") so a Node 22 image is declared once instead of once per template kind.
 *
 * AIDEV-NOTE: image references are HOHENHEIM-BUILT and, by Jelle's 2026-08-22 decision on
 * open question 5, built LOCALLY on each host from {@link #BUILD_CONTEXT} at first use --
 * there is no registry to push to. That is why {@link #BUILD_CONTEXT} is a real column
 * rather than a path derived from {@link #NAME}: an operator-authored image points its own
 * context somewhere else, and a derived path would silently build the wrong tree.
 *
 * AIDEV-NOTE: {@link #INCUS_IMAGE} is nullable ON PURPOSE. Phase 0 ships the Docker
 * variants; the Incus variants are published (distrobuilder, same package list) when the
 * Incus workspace lane is exercised. A null here is what makes the runtime-image picker
 * able to say "this image cannot run on an Incus host" instead of failing at deploy.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public class RuntimeImageModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "runtime_image");
    public static final Schema SCHEMA = new Schema();

    /** {@link #UID_MODE}: the workload runs as the instance's mapped host uid. */
    public static final String UID_MAPPED = "mapped";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    /**
     * The operator-facing identifier ({@code node-22}). Never localized: it is a key
     * operators type into template and instance settings, and it names the build context.
     */
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimFormCopy.label("name"))
        .build());

    public static final TextField DESCRIPTION = SCHEMA.addField(
        TextField.builder().name("description")
            .label(HohenheimFormCopy.label("description"))
            .build());

    public static final StringField ICON = SCHEMA.addField(StringField.builder().name("icon")
        .label(HohenheimFormCopy.label("icon"))
        .build());

    /** The Docker image reference this runtime image builds to (local, never pulled). */
    public static final StringField DOCKER_IMAGE = SCHEMA.addField(
        StringField.builder().name("docker_image")
            .label(HohenheimFormCopy.label("docker_image"))
            .help(HohenheimFormCopy.help("docker_image"))
            .build());

    /** The Incus image alias or fingerprint, or null while no Incus variant is published. */
    public static final StringField INCUS_IMAGE = SCHEMA.addField(
        StringField.builder().name("incus_image")
            .label(HohenheimFormCopy.label("runtime_incus_image"))
            .help(HohenheimFormCopy.help("runtime_incus_image"))
            .build());

    /** Repository-relative directory holding this image's Dockerfile. */
    public static final StringField BUILD_CONTEXT = SCHEMA.addField(
        StringField.builder().name("build_context")
            .label(HohenheimFormCopy.label("build_context"))
            .help(HohenheimFormCopy.help("build_context"))
            .build());

    /** Command started as the workload's main process; an instance may override it. */
    public static final StringField DEFAULT_COMMAND = SCHEMA.addField(
        StringField.builder().name("default_command")
            .label(HohenheimFormCopy.label("default_command"))
            .help(HohenheimFormCopy.help("default_command"))
            .build());

    public static final IntegerField DEFAULT_PORT = SCHEMA.addField(
        IntegerField.builder().name("default_port")
            .label(HohenheimFormCopy.label("default_port"))
            .help(HohenheimFormCopy.help("default_port"))
            .build());

    /** Build command a workspace runs inside the container; null = the image builds nothing. */
    public static final StringField DEFAULT_BUILD_COMMAND = SCHEMA.addField(
        StringField.builder().name("default_build_command")
            .label(HohenheimFormCopy.label("default_build_command"))
            .help(HohenheimFormCopy.help("default_build_command"))
            .build());

    public static final StringField WORKDIR = SCHEMA.addField(
        StringField.builder().name("workdir")
            .label(HohenheimFormCopy.label("workdir"))
            .build());

    /** Interactive shell the console and exec lanes attach with. */
    public static final StringField SHELL = SCHEMA.addField(
        StringField.builder().name("shell")
            .defaultValue("/bin/bash")
            .label(HohenheimFormCopy.label("shell"))
            .build());

    public static final EnumField UID_MODE = SCHEMA.addField(EnumField.builder("uid_mode")
        .value(UID_MAPPED, v -> v.displayName("Mapped uid").icon("user-shield")
            .label(Microcopy.of(UID_MAPPED).withFilter("scope", "uid_mode")).color("secondary"))
        .defaultValue(UID_MAPPED)
        .label(HohenheimFormCopy.label("uid_mode"))
        .help(HohenheimFormCopy.help("uid_mode"))
        .build());

    /**
     * Code-owned: re-asserted by {@code RuntimeImageSeeder} on every boot, so an edit to a
     * built-in reverts. An operator who wants a variant copies it into a new row.
     */
    public static final BooleanField BUILTIN = SCHEMA.addField(
        BooleanField.builder("builtin").defaultValue(false)
            .label(HohenheimFormCopy.label("builtin"))
            .help(HohenheimFormCopy.help("builtin"))
            .build());

    public static final BooleanField ENABLED = SCHEMA.addField(
        BooleanField.builder("enabled").defaultValue(true)
            .label(HohenheimFormCopy.label("enabled"))
            .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    /** @return the enabled images, name-ordered (the picker's offer) */
    public List<Row> findEnabled() {
        return find().where(ENABLED.eq(true)).orderBy(NAME, SortOrder.ASC).all();
    }

    /** @return the image with this name, or null */
    public Row findByName(String name) {
        return name == null ? null : find().where(NAME.eq(name)).first();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "RuntimeImage"; }

    @Override
    public String getTableName() { return "runtime_images"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
