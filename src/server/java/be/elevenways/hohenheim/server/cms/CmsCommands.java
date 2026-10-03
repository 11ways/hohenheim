package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationInvocation;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;

/** Admin writes serialize per verb; host effects explicitly run outside receipt transactions. */
final class CmsCommands {
    private static final LeaseKeys KEYS = LeaseKeys.declare(HohenheimIds.id("cms_command"));
    static final OperationCommand TRANSACTIONAL = OperationCommand.serializedBy(KEYS, OperationInvocation::operationId)
        .onDatasource("default");
    static final OperationCommand EXTERNAL = TRANSACTIONAL.execution(CommandExecution.OUTSIDE_TRANSACTION);

    private CmsCommands() {}
}
