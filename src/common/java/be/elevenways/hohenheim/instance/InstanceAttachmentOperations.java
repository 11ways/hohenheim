package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;

/**
 * The removals of what is attached to an instance: a device and a managed database attachment.
 *
 * AIDEV-NOTE: each one reaches outside the database (the daemon's devices, link networks) through its domain
 * service, which asks the instance capability itself; the gates here are open and the server authorizers decide who
 * is offered what.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceAttachmentOperations {
    private static final OperationCommand COMMAND = OperationCommand.perSubject()
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    public static final SubjectType<Row> DEVICE = SubjectType.record(InstanceDeviceModel.MODEL_ID);
    public static final SubjectType<Row> DATABASE_LINK = SubjectType.record(InstanceDatabaseModel.MODEL_ID);

    /** Detaches the device; the daemon deletes its backing volume, verified, before the row goes. */
    public static final Operation<Row, Void, Integer> DETACH_DEVICE =
        Operation.declare(HohenheimIds.id("detach_device"))
            .happened(OperationSentences.of("detach_device"))
            .label(CmsMicrocopy.of("delete"))
            .icon(Icon.TRASH)
            .one(DEVICE)
            .gate(OperationGate.open())
            .result(Integer.class)
            .command(COMMAND)
            .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
            .register();

    /** Removes the attachment: reachability is revoked at the daemon, the variable family at the next deploy. */
    public static final Operation<Row, Void, Integer> DELETE_DATABASE_LINK =
        Operation.declare(HohenheimIds.id("delete_instance_database"))
            .happened(OperationSentences.of("delete_instance_database"))
            .label(CmsMicrocopy.of("delete"))
            .icon(Icon.TRASH)
            .one(DATABASE_LINK)
            .gate(OperationGate.open())
            .result(Integer.class)
            .command(COMMAND)
            .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
            .register();

    private InstanceAttachmentOperations() {
    }
}
