package be.elevenways.hohenheim.game;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.operation.OperationInvocation;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The game-domain mapping writes: create, update and delete, each an operation over the GameDomains funnel.
 *
 * AIDEV-NOTE: the funnel is the authority (an operator, or manage on the mapping's site or either instance) and
 * materializes the proxy's forced-hosts config and the DNS output on every change, so no mapping is ever written as a
 * plain row. The gate is the admin panel's access, the only panel placing these; the update carries the mapping's lock
 * version, which the funnel's save checks.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class GameDomainOperations {
    private static final LeaseKeys KEYS = LeaseKeys.declare(HohenheimIds.id("game_domain_command"));
    private static final OperationCommand COMMAND = OperationCommand.serializedBy(KEYS, OperationInvocation::operationId);

    /** The subject of the record operations: one mapping. */
    public static final SubjectType<Row> MAPPING = SubjectType.record(GameDomainModel.MODEL_ID);

    /** The mapping form, which is every write's input. */
    public static final FormSpec FORM = FormSpec.builder()
        .add(RelationPick.of(GameDomainModel.SITE_DOMAIN_ID, SiteDomainModel.MODEL_ID).build())
        .add(RelationPick.of(GameDomainModel.BACKEND_INSTANCE_ID, InstanceModel.MODEL_ID).build())
        .add(RelationPick.of(GameDomainModel.PROXY_INSTANCE_ID, InstanceModel.MODEL_ID).build())
        .add(GameDomainModel.BACKEND_PORT)
        .add(GameDomainModel.ENABLED)
        .build();

    /**
     * The mapping form's values, one component per form entry.
     *
     * @param site_domain_id      the hostname the mapping accelerates
     * @param backend_instance_id the game server
     * @param proxy_instance_id   the proxy routing to it
     * @param backend_port        the backend's port
     * @param enabled             whether the proxy routes it
     */
    public record MappingForm(@Nullable Object site_domain_id, @Nullable Object backend_instance_id,
                              @Nullable Object proxy_instance_id, @Nullable Integer backend_port,
                              @Nullable Boolean enabled) {
    }

    private static final OperationInput<MappingForm> INPUT = OperationInput.of(FORM, MappingForm.class,
        values -> new MappingForm(values.get("site_domain_id"), values.get("backend_instance_id"),
            values.get("proxy_instance_id"),
            values.get("backend_port") instanceof Integer port ? port : null,
            values.get("enabled") instanceof Boolean flag ? flag : null));

    /** Answers the new mapping's id. */
    public static final Operation<Void, MappingForm, Integer> CREATE =
        Operation.declare(HohenheimIds.id("create_game_domain"))
            .label(words("create"))
            .noSubject()
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .input(INPUT)
            .result(Integer.class)
            .command(COMMAND.onDatasource("default"))
            .register();

    public static final Operation<Row, MappingForm, Void> UPDATE =
        Operation.declare(HohenheimIds.id("update_game_domain"))
            .label(words("save"))
            .one(MAPPING)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .input(INPUT)
            .command(COMMAND)
            .register();

    public static final Operation<Row, Void, Void> DELETE =
        Operation.declare(HohenheimIds.id("delete_game_domain"))
            .label(words("delete"))
            .one(MAPPING)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .command(COMMAND)
            .register();

    private GameDomainOperations() {
    }

    private static Microcopy words(String key) {
        return Microcopy.of(key).withFilter("scope", "game_domain");
    }
}
