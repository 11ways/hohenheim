package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * THE persistent port ledger: one row per exclusively held (server, bind address, port,
 * protocol) tuple, constrained by a UNIQUE index on the derived {@link #CLAIM_KEY}
 * (M051_PortLedgerAndHostFks). All claim/release traffic goes through
 * {@code be.elevenways.hohenheim.ports.PortLedger}, never through raw saves.
 *
 * AIDEV-NOTE: a LEDGER TABLE, not a RouteClaims-style claim-key column, by decided fork
 * (instance-tier-plan, Phase 3): a managed-process port is owned by a PROCESS INSTANCE,
 * so there is no record to hang a column on, and the table also carries cross-authority
 * capacity queries. OWNER_MODEL/OWNER_ID are therefore NULLABLE: a null owner is a port
 * held by something that is not a record (C5 managed processes), described by NOTE.
 * The {@code releasing} state (C6) is an added status column; a releasing row KEEPS its
 * claim row (and therefore keeps blocking rival claims), so the unique key never had to
 * change shape. Only an OBSERVER of the port being free may delete a row.
 */
public class PortAllocationModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "port_allocation");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    /** The host holding the port: a {@code servers.id}, never a name string. */
    public static final IntegerField SERVER_ID = SCHEMA.addField(
        IntegerField.builder().name("server_id").build());

    /** Canonical bind address: empty string means the whole-host bind (0.0.0.0 folds into it). */
    public static final StringField HOST_IP = SCHEMA.addField(
        StringField.builder().name("host_ip").build());

    public static final IntegerField PORT = SCHEMA.addField(
        IntegerField.builder().name("port").build());

    /** Lowercase protocol token, {@code tcp} or {@code udp}. */
    public static final StringField PROTOCOL = SCHEMA.addField(
        StringField.builder().name("protocol").build());

    /** The derived exclusivity key; UNIQUE, never operator-editable. */
    public static final StringField CLAIM_KEY = SCHEMA.addField(
        StringField.builder().name("claim_key").filterable(false).build());

    /** Owning model identifier ({@code hohenheim:stack_service}, ...), or null (see class note). */
    public static final StringField OWNER_MODEL = SCHEMA.addField(
        StringField.builder().name("owner_model").build());

    public static final IntegerField OWNER_ID = SCHEMA.addField(
        IntegerField.builder().name("owner_id").build());

    /** Free-form context for ports without an owning record. */
    public static final StringField NOTE = SCHEMA.addField(
        StringField.builder().name("note").build());

    /** {@link #STATUS} value: the claim is held and the port is (believed) bound. */
    public static final String STATUS_HELD = "held";

    /**
     * {@link #STATUS} value: the owner tore down (or tried to) without OBSERVING the port
     * become free; the row keeps blocking rival claims until an observer deletes it. A row
     * stuck here past {@code AttentionCollector}'s age threshold is the operator alarm.
     */
    public static final String STATUS_RELEASING = "releasing";

    /** Claim lifecycle: {@link #STATUS_HELD} or {@link #STATUS_RELEASING}. */
    public static final StringField STATUS = SCHEMA.addField(
        StringField.builder().name("status").build());

    /**
     * {@link #ALLOCATION_MODE} value: the port number was chosen and claimed BEFORE the
     * workload existed (the declared pre-allocation mode -- UDP, public game ports,
     * operator-fixed host ports). A pre-allocated claim is the owner's stable reservation:
     * it survives a stop (the kernel port is free, the NUMBER stays reserved) and only a
     * verified destroy releases it.
     */
    public static final String MODE_PREALLOCATED = "preallocated";

    /**
     * The claim's declared acquisition strategy (the instance-tier plan's fork 2
     * discriminator): null = record-after / observed (the default), or
     * {@link #MODE_PREALLOCATED}. One ledger, one claim primitive, two strategies.
     */
    public static final StringField ALLOCATION_MODE = SCHEMA.addField(
        StringField.builder().name("allocation_mode").build());

    /**
     * Which controller GENERATION wrote a record-less managed-process claim: the host
     * lease's fence at allocation time (HostLeases). The boot sweep only judges rows
     * from a LOWER generation than its own held fence, so a claim can never be freed by
     * the very controller run that wrote it, and never while a rival holds the host.
     * Null on owner-carrying claims (their record is the identity) and on legacy rows.
     */
    public static final LongField CONTROLLER_FENCE = SCHEMA.addField(
        LongField.builder("controller_fence").filterable(false).build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "PortAllocation"; }

    @Override
    public String getTableName() { return "port_allocations"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
