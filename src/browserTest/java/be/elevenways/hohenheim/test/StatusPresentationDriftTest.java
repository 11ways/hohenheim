package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.host.HostState;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.schedule.ScheduleRunStatuses;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every status an admin surface RENDERS is covered by the vocabulary that declares it, and
 * no surface classifies a status it does not know.
 *
 * AIDEV-NOTE: this exists because three surfaces each grew their own partial copy of a
 * status vocabulary and then quietly disagreed with the producer. The schedule-run cell
 * called everything that was not "completed" destructive (so a RUNNING chain was red), the
 * site-databases tab knew three of DatabaseModel's four statuses (so destroy_failed -- the
 * one carrying an operator obligation -- rendered as a neutral grey pill), and the host
 * cell's dot switch had a {@code default -> "online"} arm (so an unknown state looked
 * healthy). None of the three could fail a test, because a fallback branch always
 * rendered SOMETHING. These assertions read the producers' own declarations.
 */
@DisplayName("Status presentation drift")
class StatusPresentationDriftTest {

    @Test
    @DisplayName("the schedule-run badge covers every status the framework can store")
    void scheduleRunStatusesAreExhaustive() {
        // 1. The framework declares its run statuses as bare String constants, so the
        //    presentation table is bound to them by NAME: STATUS_* on the run model.
        List<String> declared = constantsWithPrefix(RecordScheduleRunModel.class, "STATUS_");
        assertThat(declared)
            .as("step 1: the run model still declares its statuses as STATUS_* constants")
            .hasSizeGreaterThanOrEqualTo(6);

        // 2. Every one of them has a declared label/icon/colour here. A status added
        //    upstream fails HERE rather than rendering as an unstyled raw token.
        assertThat(ScheduleRunStatuses.FIELD.getValues().keySet())
            .as("step 2: the presentation table covers exactly the framework's statuses")
            .containsExactlyInAnyOrderElementsOf(declared);

        // 3. Each carries the three facts the badge needs, and only the genuinely good
        //    outcome is green -- "completed with failures" must not read as success.
        for (EnumField.EnumValue value : ScheduleRunStatuses.FIELD.getValues().values()) {
            assertThat(value.getIcon()).as("step 3: %s declares an icon", value.getKey()).isNotNull();
            assertThat(value.getColor()).as("step 3: %s declares a colour", value.getKey()).isNotNull();
            assertThat(value.getMicrocopy()).as("step 3: %s declares a label", value.getKey()).isNotNull();
        }
        assertThat(ScheduleRunStatuses.FIELD.getValues()
                .get(RecordScheduleRunModel.STATUS_COMPLETED).getColor())
            .as("step 3: a clean completion is the success colour").isEqualTo("success");
        assertThat(ScheduleRunStatuses.FIELD.getValues()
                .get(RecordScheduleRunModel.STATUS_COMPLETED_WITH_FAILURES).getColor())
            .as("step 3: a partial completion is neither success nor outright failure")
            .isEqualTo("warning");
        assertThat(ScheduleRunStatuses.FIELD.getValues()
                .get(RecordScheduleRunModel.STATUS_RUNNING).getColor())
            .as("step 3: a chain still running is not painted as a failure")
            .isNotEqualTo("destructive");

        // 4. An unknown status gets no colour at all rather than a wrong one.
        assertThat(ScheduleRunStatuses.badgeFor("teleported").known())
            .as("step 4: an unknown status renders as plain text, never a styled verdict")
            .isFalse();
        assertThat(ScheduleRunStatuses.badgeFor(null))
            .as("step 4: a missing status is no badge").isNull();
    }

    @Test
    @DisplayName("the database status badge covers every status the model declares")
    void databaseStatusesAreExhaustive() {
        // 1. The model's STATUS_* constants ARE the vocabulary the site-databases tab renders.
        List<String> declared = constantsWithPrefix(DatabaseModel.class, "STATUS_");
        assertThat(DatabaseModel.STATUS.getValues().keySet())
            .as("step 1: the status field offers exactly the declared statuses")
            .containsExactlyInAnyOrderElementsOf(declared);

        // 2. Each carries the badge facets the tab and the list both read off the field.
        for (EnumField.EnumValue value : DatabaseModel.STATUS.getValues().values()) {
            assertThat(value.getIcon()).as("step 2: %s declares an icon", value.getKey()).isNotNull();
            assertThat(value.getColor()).as("step 2: %s declares a colour", value.getKey()).isNotNull();
            assertThat(value.getMicrocopy()).as("step 2: %s is localizable", value.getKey()).isNotNull();
        }

        // 3. The state an operator must act on is destructive, not neutral.
        assertThat(DatabaseModel.STATUS.getValues().get(DatabaseModel.STATUS_DESTROY_FAILED).getColor())
            .as("step 3: a failed destroy shouts").isEqualTo("destructive");
    }

    @Test
    @DisplayName("only a healthy host reads as online")
    void hostStateNeverDegradesToOnline() {
        // 1. Every state carries a dot, so none can fall through to a default.
        for (HostState state : HostState.values()) {
            assertThat(state.dot()).as("step 1: %s declares a dot", state).isNotBlank();
            assertThat(state.token()).as("step 1: %s declares a token", state).isNotBlank();
        }

        // 2. Green belongs to OK alone -- the whole point of the type.
        List<HostState> online = Arrays.stream(HostState.values())
            .filter(state -> "online".equals(state.dot())).toList();
        assertThat(online).as("step 2: only OK looks healthy").containsExactly(HostState.OK);

        // 3. Every state that is not OK says something; OK shows the daemon label instead.
        for (HostState state : HostState.values()) {
            if (state == HostState.OK) {
                assertThat(state.wording()).as("step 3: OK has no state word").isNull();
            } else {
                assertThat(state.wording()).as("step 3: %s carries wording", state).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("every notification event is offered as a subscription")
    void notificationEventsAreDerived() {
        // 1. ALL is derived, so it can never lag a declared event.
        assertThat(NotificationEvents.ALL)
            .as("step 1: one entry per declared event")
            .hasSize(NotificationEvents.values().length);

        // 2. Every declared event is a known subscription token -- the validator that
        //    refuses unknown events and the picker that offers them read the same list.
        for (NotificationEvents event : NotificationEvents.values()) {
            assertThat(NotificationEvents.isKnown(event.token()))
                .as("step 2: %s is selectable", event).isTrue();
        }

        // 3. Tokens are distinct: two events sharing one token would silently merge
        //    subscriptions.
        assertThat(NotificationEvents.ALL).as("step 3: tokens are unique").doesNotHaveDuplicates();
        assertThat(NotificationEvents.isKnown("not_an_event"))
            .as("step 3: an undeclared token is refused").isFalse();
    }

    /** Public static String constants of a class whose NAME starts with the prefix. */
    private static List<String> constantsWithPrefix(Class<?> type, String prefix) {
        List<String> values = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!field.getName().startsWith(prefix)
                    || !Modifier.isStatic(field.getModifiers())
                    || !Modifier.isPublic(field.getModifiers())
                    || field.getType() != String.class) {
                continue;
            }
            try {
                values.add((String) field.get(null));
            } catch (IllegalAccessException unreadable) {
                throw new IllegalStateException("cannot read " + field.getName(), unreadable);
            }
        }
        return values;
    }
}
