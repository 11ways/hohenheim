package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * A single managed runtime unit (the instance tier's record): one container today,
 * Incus containers and VMs later. The FOURTH consumer of the shared runtime machinery
 * (owner labels, port ledger, reconciler, canonical host key) -- never a parallel
 * authority beside Site, Stack and Database.
 *
 * AIDEV-NOTE: deliberately NO owner_principal_id column -- ownership is grant-derived
 * (HohenheimAccess.sameOwner/manageSubjectsOf over the manage-grant subject set), and a
 * column would be a second authority that drifts the first time a grant changes. Also
 * deliberately NO fence/generation/operation-id columns: no node identity or lease
 * exists yet, and a column that reads like enforcement while enforcing nothing is the
 * exact defect shape the instance-tier plan bans.
 */
public class InstanceModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance");
    public static final Schema SCHEMA = new Schema();

    /** {@link #STATUS}: record exists, nothing was ever deployed. */
    public static final String STATUS_CREATED = "created";

    /** {@link #STATUS}: last deploy reached a running container. */
    public static final String STATUS_RUNNING = "running";

    /** {@link #STATUS}: deliberately stopped (or destroyed under a soft delete). */
    public static final String STATUS_STOPPED = "stopped";

    /** {@link #STATUS}: the last runtime operation failed; the daemon may disagree. */
    public static final String STATUS_ERROR = "error";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    // User data, NOT localized (the plan's explicit call: names are the user's own words).
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .build());

    // ONE discriminator: the kind implies the runtime (docker_container now; incus_container
    // and vm reserved). Values enumerate the registry live; stored value = "hohenheim:<kind>".
    public static final EnumField KIND = SCHEMA.addField(
        RegistryEnumField.builder("kind")
            .registry(InstanceKindRegistry.REGISTRY)
            .label(HohenheimFormCopy.label("kind"))
            .help(HohenheimFormCopy.help("instance_kind"))
            .build());

    // Polymorphic per-kind settings. AIDEV-NOTE: the dynamic (schemaFrom) form entry
    // REWRITES this whole map on every admin save (the SiteModel.SECURITY_REPORT_TOKEN
    // lesson) -- anything that must survive an admin save gets a COLUMN, never a key here.
    public static final SchemaField SETTINGS = SCHEMA.addField(
        SchemaField.builder("settings")
            .schemaFrom("kind")
            .label(HohenheimFormCopy.label("settings"))
            .build());

    // The host FK (servers.id). Every write folds through ServerModel.canonicalServerId
    // (the beforeValidate hook below) -- never a re-spelling; null means the local daemon.
    public static final IntegerField SERVER_ID = SCHEMA.addField(
        IntegerField.builder().name("server_id")
            .label(HohenheimFormCopy.label("server"))
            .build());

    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_CREATED, v -> v.displayName("Created").icon("circle")
            .label(Microcopy.of("created").withFilter("scope", "instance_status")).color("gray"))
        .value(STATUS_RUNNING, v -> v.displayName("Running").icon("circle-play")
            .label(Microcopy.of("running").withFilter("scope", "instance_status")).color("green"))
        .value(STATUS_STOPPED, v -> v.displayName("Stopped").icon("circle-stop")
            .label(Microcopy.of("stopped").withFilter("scope", "instance_status")).color("orange"))
        .value(STATUS_ERROR, v -> v.displayName("Error").icon("circle-exclamation")
            .label(Microcopy.of("error").withFilter("scope", "instance_status")).color("red"))
        .defaultValue(STATUS_CREATED)
        .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());
    public static final DateTimeField DELETED_AT = SCHEMA.addField(DateTimeField.builder().name("deleted_at").build());

    static {
        SCHEMA.setDisplayFields(NAME);
        // Soft delete by hand (the SiteModel shape): DELETED_AT is lifecycle state, and the
        // grant declaration's liveWhen predicate keys on it (HohenheimAccess).
        SCHEMA.addLifecycleField(DELETED_AT);
        // Fold any host spelling (row id, name, registry key) onto THE canonical servers.id
        // at write time -- a fifth spelling of "which host" is exactly what C3 removed.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && row.has(SERVER_ID.getName())
                    && row.get(SERVER_ID.getName()) != null) {
                row.set(SERVER_ID, ServerModel.canonicalServerId(row.get(SERVER_ID.getName())));
            }
        });
        // A delete carries only CRITERIA (row is null in the hook context), so the doomed
        // owners are captured before the rows disappear and their port claims parked in
        // "releasing" after -- an afterRemove-only hook would silently release NOTHING and
        // the ports would be permanently unclaimable.
        SCHEMA.addBeforeRemoveHook(PortLedger::captureDoomedOwners);
        SCHEMA.addAfterRemoveHook(PortLedger::releaseDoomedOwners);
    }

    /** Every instance that is not soft-deleted, newest first. */
    public List<Row> findLive() {
        return find()
            .where(DELETED_AT.isNull())
            .orderBy(CREATED_AT, SortOrder.DESC)
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Instance"; }

    @Override
    public String getTableName() { return "instances"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
