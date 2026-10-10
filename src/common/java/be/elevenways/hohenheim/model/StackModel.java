package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A managed multi-container Docker stack: one private policied bridge network, named volumes,
 * and an ordered set of services (see {@link StackServiceModel}). The record is the
 * DESIRED state; deploys are explicit (never save-triggered) and live state is read
 * from Docker on demand. Every service IS an owned instance of the
 * {@code hohenheim:stack_service} kind (see StackInstances); this record owns the shared
 * link network its services reach each other over.
 */
public class StackModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("stack");
    public static final Schema SCHEMA = new Schema();

    /** {@link #STATUS} value before the first deploy. */
    public static final String STATUS_INACTIVE = "inactive";

    /** {@link #STATUS} value while a deploy is running. */
    public static final String STATUS_DEPLOYING = "deploying";

    /** {@link #STATUS} value when every enabled service runs (and is healthy where checked). */
    public static final String STATUS_ACTIVE = "active";

    /** {@link #STATUS} value when some but not all services are running/healthy. */
    public static final String STATUS_DEGRADED = "degraded";

    /** {@link #STATUS} value when the last deploy failed or nothing runs. */
    public static final String STATUS_FAILED = "failed";

    /** {@link #STATUS} value after an explicit stop. */
    public static final String STATUS_STOPPED = "stopped";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("name"))
        .help(HohenheimMicrocopy.HELP.of("stack_name"))
        .build());

    /** A stack, service or volume name becomes a Docker network, container and volume name segment: one DNS label. */
    public static final int MAX_NAME_LENGTH = 63;

    /** @return whether a stack, service or named-volume name has the {@link LowercaseNames} shape and fits a label */
    public static boolean isValidName(@Nullable String name) {
        return LowercaseNames.isValid(name, MAX_NAME_LENGTH);
    }

    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled")
        .defaultValue(true)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("enabled"))
        .build());

    /** The host this stack deploys to: a {@code servers.id} FK, never a name string. */
    public static final IntegerField SERVER_ID = SCHEMA.addField(IntegerField.builder().name("server_id")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("server"))
        .help(HohenheimMicrocopy.HELP.of("server"))
        .build());

    public static final StringField REGISTRY_SERVER = SCHEMA.addField(StringField.builder().name("registry_server")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("registry_server"))
        .help(HohenheimMicrocopy.HELP.of("registry_server"))
        .build());

    public static final StringField REGISTRY_USER = SCHEMA.addField(StringField.builder().name("registry_user")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("registry_user"))
        .build());

    public static final StringField REGISTRY_PASSWORD = SCHEMA.addField(StringField.builder().name("registry_password")
        .secret()
        .encrypted()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("registry_password"))
        .build());

    public static final TextField DESCRIPTION = SCHEMA.addField(TextField.builder().name("description")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("description"))
        .build());

    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_INACTIVE, v -> v.displayName("Inactive")
            .label(HohenheimMicrocopy.STACK_STATUS.of(STATUS_INACTIVE)).icon("circle-pause")
            .color(BadgeVariant.SECONDARY))
        .value(STATUS_DEPLOYING, v -> v.displayName("Deploying")
            .label(HohenheimMicrocopy.STACK_STATUS.of(STATUS_DEPLOYING)).icon("rotate").color(BadgeVariant.WARNING))
        .value(STATUS_ACTIVE, v -> v.displayName("Active")
            .label(HohenheimMicrocopy.STACK_STATUS.of(STATUS_ACTIVE)).icon("circle-check").color(BadgeVariant.SUCCESS))
        .value(STATUS_DEGRADED, v -> v.displayName("Degraded")
            .label(HohenheimMicrocopy.STACK_STATUS.of(STATUS_DEGRADED)).icon("triangle-exclamation")
            .color(BadgeVariant.WARNING))
        .value(STATUS_FAILED, v -> v.displayName("Failed")
            .label(HohenheimMicrocopy.STACK_STATUS.of(STATUS_FAILED)).icon("circle-xmark")
            .color(BadgeVariant.DESTRUCTIVE))
        .value(STATUS_STOPPED, v -> v.displayName("Stopped")
            .label(HohenheimMicrocopy.STACK_STATUS.of(STATUS_STOPPED)).icon("circle-stop")
            .color(BadgeVariant.SECONDARY))
        .defaultValue(STATUS_INACTIVE)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("status")).build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        // Every stack save is ONE write transaction: the row write and whatever a save hook
        // derives from it commit or fail together -- the SiteDomainModel/RouteClaims shape.
        SCHEMA.saveAtomically();
        // A stack always has a concrete host: defaulting the FK at create time keeps
        // the port ledger's claim keys total (a null host would split the claim set).
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && row.get(ID) == null && row.get(SERVER_ID) == null) {
                row.set(SERVER_ID, ServerModel.localServerId());
            }
        });
        // AIDEV-NOTE: a stack save can MOVE the stack to another host, and the port
        // claims of its services key on that host. Since the tier lowered onto the
        // instance contract those claims belong to the services' owned INSTANCES and are
        // re-keyed by the next DEPLOY, which is also the only moment a host move actually
        // takes effect. Re-syncing them at save time would have written claims for a host
        // no container runs on yet.
    }


    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Stack"; }

    @Override
    public String getTableName() { return "stacks"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
