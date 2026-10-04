package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectArity;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.server.operation.RowDeleteOperations;

/**
 * The plain row deletes of the operator's instance children, each its entry's canonical delete.
 *
 * AIDEV-NOTE: declared once per model and arity, and loaded by {@link #init()} at boot so the lifecycle declaration
 * is verified with every other operation. These entries live on /admin only, so the gate is the admin panel's own
 * permission; the entries' scopes still narrow which rows a delete accepts.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceChildDeletes {

    private static final OperationGate OPERATOR = OperationGate.permission(HohenheimSources.ADMIN_ACCESS);

    /** Removes one config file of an instance; a generated one is refused by the model's attribution guard. */
    public static final Operation<Row, Void, Integer> FILE =
        RowDeleteOperations.delete(InstanceFileModel.class, SubjectArity.ONE, OPERATOR);

    /** Removes one quota override. */
    public static final Operation<Row, Void, Integer> QUOTA =
        RowDeleteOperations.delete(InstanceQuotaModel.class, SubjectArity.ONE, OPERATOR);

    /** Removes one schedule run, which is cleanup: a run is evidence and never edited. */
    public static final Operation<Row, Void, Integer> SCHEDULE_RUN =
        RowDeleteOperations.delete(RecordScheduleRunModel.class, SubjectArity.ONE, OPERATOR);

    private InstanceChildDeletes() {
    }

    /** Loads the class, declaring the deletes; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }
}
