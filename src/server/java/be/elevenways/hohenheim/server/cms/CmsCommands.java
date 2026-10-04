package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.command.CommandExecution;

/** Admin writes use core's subject locks; host effects run outside receipt transactions. */
final class CmsCommands {
    static final OperationCommand TRANSACTIONAL = OperationCommand.perSubject()
        .onDatasource("default");
    static final OperationCommand EXTERNAL = TRANSACTIONAL.execution(CommandExecution.OUTSIDE_TRANSACTION);

    private CmsCommands() {}
}
