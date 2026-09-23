package be.elevenways.hohenheim.test.model;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instance status vocabulary has ONE home ({@link InstanceStatus}) and every list or
 * predicate that classifies a status is derived from the member's facts: the status field
 * declares exactly the members, the lists project the facts, and an unknown stored token is
 * classified as nothing (fail closed) -- where the old denylist made it operable.
 */
class InstanceStatusVocabularyTest {

    @Test
    void everyStatusFactHasOneHomeAndAnUnknownStatusFailsClosed() {
        // 1. The stored field declares exactly the members, token for token.
        List<String> tokens = InstanceStatus.tokensWhere(status -> true);
        assertThat(((EnumField) InstanceModel.STATUS).getValues().keySet())
            .as("step 1: the status field offers exactly the enum's tokens")
            .containsExactlyElementsOf(tokens);

        // 2. The three lists callers read are projections of the facts, not copies.
        assertThat(InstanceModel.LIVE_GUEST_STATUSES)
            .as("step 2: the isolation sweeps' filter is the liveGuest fact")
            .containsExactlyElementsOf(InstanceStatus.tokensWhere(InstanceStatus::liveGuest))
            .containsExactlyInAnyOrder(InstanceModel.STATUS_RUNNING, InstanceModel.STATUS_STARTING,
                InstanceModel.STATUS_ERROR);
        assertThat(InstanceModel.PROTECTED_STATUSES)
            .as("step 2: the protected statuses are the non-operable ones")
            .containsExactlyInAnyOrder(InstanceModel.STATUS_CAPTURING,
                InstanceModel.STATUS_RESTORING, InstanceModel.STATUS_MIGRATING);
        assertThat(InstanceModel.SERVABLE_STATUSES)
            .as("step 2: everything but created and stopped is servable")
            .doesNotContain(InstanceModel.STATUS_CREATED, InstanceModel.STATUS_STOPPED)
            .hasSize(InstanceStatus.values().length - 2);

        // 3. isOperable follows the fact, reads a null status as the declared default, and
        //    FAILS CLOSED on a token no member declares.
        assertThat(InstanceModel.isOperable(rowWith(InstanceModel.STATUS_RUNNING)))
            .as("step 3: a running instance is operable").isTrue();
        assertThat(InstanceModel.isOperable(rowWith(InstanceModel.STATUS_CAPTURING)))
            .as("step 3: a capturing one is not").isFalse();
        assertThat(InstanceModel.isOperable(rowWith(null)))
            .as("step 3: a null status reads as created").isTrue();
        assertThat(InstanceModel.isOperable(rowWith("hibernating")))
            .as("step 3: an unknown status is NOT operable (it used to be, by denylist)")
            .isFalse();
        assertThat(InstanceStatus.forToken("hibernating"))
            .as("step 3: and resolves to no member at all").isNull();
    }

    private static Row rowWith(String status) {
        Row row = new Row();
        row.set(InstanceModel.STATUS.getName(), status);
        return row;
    }
}
