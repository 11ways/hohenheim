package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.OptionMeter;
import be.elevenways.zenit.common.edit.SiblingJudgement;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A host pick shows how full each host is and refuses a host without room for what is being created, in the words the
 * run's own booking refuses with, before the run.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class HostPickRoomTest extends HohenheimTestBase {

    private static final String PREFIX = "host-pick-room-";
    private static final String CONTAINER = "hohenheim:docker_container";

    private static int full;
    private static int roomy;
    private static long filled;

    @BeforeAll
    static void seed() {
        full = HostFixtures.admittedDockerHost(PREFIX + "full", 1300);
        roomy = HostFixtures.admittedDockerHost(PREFIX + "roomy", 16384);
        long budget = Objects.requireNonNull(InstanceCapacity.budgetMbOf(server(full)));
        filled = budget - 3;
        InstanceCapacity.reserve(full, filled);
    }

    @AfterAll
    static void cleanUp() {
        InstanceCapacity.release(full, filled);
        HardDeletes.row(Models.get(ServerModel.class), server(full));
        HardDeletes.row(Models.get(ServerModel.class), server(roomy));
    }

    @Test
    void aFullHostShowsItsMeterAndIsRefusedInTheWhereStep() {
        // 1. A measured host is offered with its standing as its line and its booked memory as its meter: the full
        //    host's bar is all but full, the roomy one's empty.
        FieldOption<Integer> fullOption = ServerParts.hostOption(server(full));
        OptionMeter meter = Objects.requireNonNull(fullOption.meter(), "step 1: a measured host carries a meter");
        assertThat(meter.perMille()).as("step 1: 3 MB free of its budget reads full").isGreaterThanOrEqualTo(990);
        assertThat(meter.text().key()).as("step 1: in the Hosts list's words").isEqualTo("host_option_memory");
        assertThat(Objects.requireNonNull(fullOption.description()).key())
            .as("step 1: the line is the standing alone, the memory moved into the meter")
            .isNotEqualTo("host_option_facts");
        assertThat(Objects.requireNonNull(ServerParts.hostOption(server(roomy)).meter()).perMille())
            .as("step 1: an empty host's bar is empty").isZero();

        // 2. The kind-led Where step judges the full host by the run's own booking rule at the kind's default memory:
        //    refused, naming the host, what is needed and what is free.
        SiblingJudgement<?, Integer> judged = Objects.requireNonNull(
            InstanceParts.hostPick(InstanceTemplateOperations.SERVER_ID).judgement());
        EditContext create = EditContext.of(TenantConduits.operator());
        Map<String, String> container = Map.of("kind", CONTAINER);
        Microcopy refusal = judged.refusal(create, container, full);
        assertThat(refusal).as("step 2: the full host is refused in the Where step").isNotNull();
        assertThat(Objects.requireNonNull(refusal).key()).as("step 2: with the run's own refusal")
            .isEqualTo("host_capacity_reached");
        assertThat(refusal.args().get("free")).as("step 2: saying what is free").isEqualTo(3L);

        // 3. The roomy host stays a choice.
        assertThat(judged.refusal(create, container, roomy)).as("step 3: a host with room is a choice").isNull();
    }

    private static Row server(int id) {
        return Models.get(ServerModel.class).findById(id);
    }
}
