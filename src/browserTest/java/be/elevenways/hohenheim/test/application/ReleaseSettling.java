package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;

/**
 * Whether an application's release work is over: its latest operation succeeded AND nothing holds the application.
 *
 * AIDEV-NOTE: the drain writes SUCCEEDED inside its claim (an outcome write is fenced by the claim) and releases the
 * claim only afterwards, so the status alone opens a window in which a REFUSE-contention rollback is turned away as
 * instance_operation_in_progress. runIfIdle asks the lock itself.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class ReleaseSettling {

    private ReleaseSettling() {
    }

    static boolean settled(int applicationId) {
        Row latest = Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
            .first();
        return latest != null
            && ReleaseOperationModel.LIFECYCLE.is(latest.get(ReleaseOperationModel.STATUS), OperationStatus.SUCCEEDED)
            && InstanceOperationLock.production().runIfIdle(applicationId, () -> { });
    }
}
