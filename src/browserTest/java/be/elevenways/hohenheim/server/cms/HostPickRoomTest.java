package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.host.HostCapacityView;
import be.elevenways.hohenheim.host.HostStanding;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.DiskSpace;
import be.elevenways.hohenheim.server.host.HostDisk;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.Reader;
import be.elevenways.protoblast.common.i18n.wording.Wording;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.OptionMeter;
import be.elevenways.zenit.common.edit.SiblingJudgement;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.Zenit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
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
class HostPickRoomTest extends HohenheimHttpTestBase {

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
    void aFullHostShowsItsMetersAndIsRefusedInTheWhereStep() {
        // 1. A measured host is offered with its standing as its line and its booked memory as its first meter: the
        //    full host's bar is all but full, the roomy one's empty.
        FieldOption<Integer> fullOption = ServerParts.hostOption(server(full));
        assertThat(fullOption.meters()).as("step 1: a measured host without a disk reading carries one meter")
            .hasSize(1);
        OptionMeter meter = fullOption.meters().get(0);
        assertThat(meter.perMille()).as("step 1: 3 MB free of its budget reads full").isGreaterThanOrEqualTo(990);
        assertThat(meter.text().key()).as("step 1: in the Hosts list's words").isEqualTo("host_option_memory");
        assertThat(Objects.requireNonNull(fullOption.description()).key())
            .as("step 1: the line is the standing alone, the memory moved into the meter")
            .isNotEqualTo("host_option_facts");
        assertThat(ServerParts.hostOption(server(roomy)).meters().get(0).perMille())
            .as("step 1: an empty host's bar is empty").isZero();

        // 1b. A host with a fresh disk reading carries its disk as the second meter, in the Hosts list's words.
        long gib = 1024L * 1024 * 1024;
        Row measured = server(roomy);
        HostDisk.record(measured, new DiskSpace(80 * gib, 60 * gib), Now.instant());
        Models.get(ServerModel.class).save(measured);
        List<OptionMeter> meters = ServerParts.hostOption(server(roomy)).meters();
        assertThat(meters).as("step 1b: memory, then disk").hasSize(2);
        assertThat(meters.get(1).perMille()).as("step 1b: 20 of 80 GiB used").isEqualTo(250);
        assertThat(meters.get(1).text().key()).as("step 1b: in the Hosts list's disk words, named")
            .isEqualTo("host_option_disk");
        assertThat(((Microcopy) meters.get(1).text().args().get("disk")).key()).as("step 1b: the list's own reading")
            .isEqualTo("disk_used");

        // 1c. The host page's Bookable fact reads what is still free to book from the same ledger the meter reads:
        //     3 MB free of the budget, never the whole budget beside a "3 MB free" warning.
        HostCapacityView ledger = InstanceCapacity.viewOf(server(full), full);
        assertThat(ledger.freeMb()).as("step 1c: the ledger leaves 3 MB free").isEqualTo(3);
        long budget = ByteUnit.MEGABYTE.bytes(ledger.bookableMb());
        assertThat(bookableFact(ledger, "en")).as("step 1c: free of the budget")
            .isEqualTo(size(ByteUnit.MEGABYTE.bytes(3), "en") + " free of " + size(budget, "en"));
        assertThat(bookableFact(ledger, "nl")).as("step 1c: in Dutch too, in its own symbols")
            .isEqualTo(size(ByteUnit.MEGABYTE.bytes(3), "nl") + " vrij van " + size(budget, "nl"));
        LocaleChain english = LocaleChain.ofTags("en");
        MessageResolver resolver = Zenit.getMessageResolver();
        assertThat(ServerOverviewState.capacityFacts(ledger, english, resolver).stream().map(fact -> fact.label())
            .toList()).as("step 1c: Booked and Bookable, no Budget fact repeating its figure").containsExactly(
                HohenheimMicrocopy.SERVER_OVERVIEW.of("booked").resolve(english, resolver),
                HohenheimMicrocopy.SERVER_OVERVIEW.of("bookable").resolve(english, resolver));

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
        assertThat(refusal.args().get("free")).as("step 2: saying what is free, as a size")
            .isEqualTo(ByteUnit.MEGABYTE.bytes(3));

        // 3. The roomy host stays a choice.
        assertThat(judged.refusal(create, container, roomy)).as("step 3: a host with room is a choice").isNull();
    }

    /**
     * The Hosts list's standing asks the run's own room rule, at the smallest new app's default memory: a host without
     * room for any new app reads full, in the list, the host pick's line and the gate's host verdict alike.
     */
    @Test
    void aHostWithoutRoomForAnyNewAppReadsFullInTheHostsList() {
        // 1. The full host is admitted and measured, yet takes no new app: its standing says so, with the reason.
        HostVerdict fullVerdict = HostVerdict.of(server(full));
        assertThat(fullVerdict.standing()).as("step 1: a full host takes no new apps")
            .isEqualTo(HostStanding.REFUSING);
        Microcopy reason = Objects.requireNonNull(fullVerdict.reason(), "step 1: the standing says why");
        assertThat(reason.key()).as("step 1: it is full").isEqualTo("host_full");
        assertThat(reason.args().get("free")).as("step 1: saying what is free, as a size")
            .isEqualTo(ByteUnit.MEGABYTE.bytes(3));
        assertThat((Long) reason.args().get("smallest")).as("step 1: and what the smallest new app needs")
            .isGreaterThan(ByteUnit.MEGABYTE.bytes(3));

        // 2. The gate's host verdict is that same refusal, so the list and the pick cannot disagree.
        assertThat(Objects.requireNonNull(InstancePlacement.hostRefusal(server(full))).key())
            .as("step 2: the host verdict is the list's").isEqualTo("host_full");

        // 3. A host with room still takes new apps.
        assertThat(HostVerdict.of(server(roomy)).standing()).as("step 3: the roomy host takes new apps")
            .isEqualTo(HostStanding.TAKING);
    }

    /** @return the value of the host page's Bookable fact, in the given language */
    private static String bookableFact(HostCapacityView ledger, String tag) {
        LocaleChain locales = LocaleChain.ofTags(tag);
        String label = HohenheimMicrocopy.SERVER_OVERVIEW.of("bookable").resolve(locales, Zenit.getMessageResolver());
        return ServerOverviewState.capacityFacts(ledger, locales, Zenit.getMessageResolver()).stream()
            .filter(fact -> fact.label().equals(label)).findFirst().orElseThrow().value();
    }

    private static Row server(int id) {
        return Models.get(ServerModel.class).findById(id);
    }

    /** @return a byte count as a size in the language's own symbols */
    private static String size(long bytes, String tag) {
        return Wording.bytes(Reader.of(LocaleChain.ofTags(tag), Zenit.getMessageResolver()), bytes,
            Zenit.getMessageResolver());
    }
}
