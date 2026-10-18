package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;

/**
 * Whether an application's release work is over: its latest operation succeeded.
 *
 * AIDEV-NOTE: the status alone is the condition, as it is for an API client. A release reads succeeded only once its
 * drain let go of the application (ReleaseEngine publishes an operation's outcome through
 * InstanceOperationLock.afterRelease), so a REFUSE-contention rollback right after it is admitted; asking the lock
 * here as well would hide a regression of exactly that.
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
            && ReleaseOperationModel.LIFECYCLE.is(latest.get(ReleaseOperationModel.STATUS), OperationStatus.SUCCEEDED);
    }
}
