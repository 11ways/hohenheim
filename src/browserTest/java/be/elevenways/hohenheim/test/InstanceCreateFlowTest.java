package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instance CREATE journey the admin-UI wave promises: choice cards decide the kind,
 * the host and runtime-image picks NARROW from it client-side, the submit persists the
 * matching record -- and the server re-narrows a hand-posted host that the picker would
 * never have offered (the falsification of the dependent-pick guard).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceCreateFlowTest extends HohenheimTestBase {

    private static final String OPEN_SELECT_POPUP = "he-bottom .pl-select-popup[data-open]";
    private static final String HOST_SELECT = "pl-select[name='server_id']";
    private static final String IMAGE_SELECT = "pl-select[name='runtime_image_id']";

    private static Integer dockerHostId;
    private static Integer incusHostId;

    @BeforeAll
    static void seedHosts() {
        dockerHostId = host("cf-docker-host", ServerModel.RUNTIME_DOCKER, "btrfs");
        incusHostId = host("cf-incus-host", ServerModel.RUNTIME_INCUS, "none");
    }

    private static Integer host(String name, String runtime, String volumeBackend) {
        var servers = Models.get(ServerModel.class);
        Row existing = servers.find().where(ServerModel.NAME.eq(name)).first();
        if (existing != null) {
            return existing.get(ServerModel.ID);
        }
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.MODE, ServerModel.MODE_SSH);
        row.set(ServerModel.RUNTIME, runtime);
        row.set(ServerModel.VOLUME_BACKEND, volumeBackend);
        row.set(ServerModel.SSH_TARGET, "root@" + name + ".test");
        servers.save(row);
        return row.get(ServerModel.ID);
    }

    private void clickKindCard(String kind) {
        page.click("pl-choice-group[name='kind'] pl-choice-card[data-value='hohenheim:"
            + kind + "'] button");
        waitForReactiveIdle();
        waitForReactiveIdle();
    }

    private void openPlSelect(String hostSelector) {
        page.click(hostSelector + " .pl-select-field");
        page.waitForSelector(OPEN_SELECT_POPUP);
    }

    private void closeOpenPopup() {
        page.evaluate(
            "document.body.dispatchEvent(new PointerEvent('pointerdown', {bubbles: true}))");
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);
    }

    private String hostOption(Integer hostId) {
        return OPEN_SELECT_POPUP + " div[role='option'][data-value='" + hostId + "']";
    }

    /** One create-form load walked through kinds, hosts and the image pick. */
    @Test
    @Order(1)
    void kindCardsDriveTheDependentPicks() {
        navigateToApp("/admin/instances/new");
        waitForHydration();

        // 1. The kind is a CARD choice: every authorable kind, each with an icon and a
        //    one-sentence description; the generated kinds are simply not offered.
        var cards = page.locator(
            "pl-choice-group[name='kind'] pl-choice-card[data-value^='hohenheim:']");
        assertThat(cards.count()).as("step 1: the five authorable kinds are cards")
            .isEqualTo(5);
        String cardsText = cards.allTextContents().toString();
        assertThat(cardsText).as("step 1: every card explains itself")
            .contains("persistent dev box", "health-gated releases", "container image");
        assertThat(cardsText).as("step 1: generated kinds are not offered")
            .doesNotContain("Release", "Database container", "Stack service");

        // 2. No kind chosen: host and runtime image cannot narrow, so both are disabled.
        waitForSelector(HOST_SELECT + "[disabled]");
        waitForSelector(IMAGE_SELECT + "[disabled]");

        // Reload marker: every narrowing step below must be client-side.
        page.evaluate("window.__cfNoReloadMarker = 'alive'");

        // 3. A Docker-only kind offers Docker hosts and never the Incus one.
        clickKindCard("docker_container");
        page.waitForCondition(() -> page.locator(HOST_SELECT + "[disabled]").count() == 0);
        openPlSelect(HOST_SELECT);
        page.waitForSelector(hostOption(dockerHostId));
        assertCount(hostOption(incusHostId), 0);
        closeOpenPopup();

        // ...and the runtime image stays disabled: a raw container ignores the column.
        assertCount(IMAGE_SELECT + "[disabled]", 1);

        // 4. An Incus-only kind flips the offer around.
        clickKindCard("vm");
        page.waitForCondition(() -> page.locator(HOST_SELECT + "[disabled]").count() == 0);
        openPlSelect(HOST_SELECT);
        page.waitForSelector(hostOption(incusHostId));
        assertCount(hostOption(dockerHostId), 0);
        closeOpenPopup();

        // 5. A workspace runs on both runtimes but DEMANDS a quota-capable volume
        //    backend, so only the btrfs host qualifies -- the Incus host (backend none)
        //    is out even though the runtime matches.
        clickKindCard("workspace");
        page.waitForCondition(() -> page.locator(HOST_SELECT + "[disabled]").count() == 0);
        openPlSelect(HOST_SELECT);
        page.waitForSelector(hostOption(dockerHostId));
        assertCount(hostOption(incusHostId), 0);

        // 6. A workspace runs inside a runtime image: the pick resolves, and the seeded
        //    catalog answers (RuntimeImageSeeder ships node-22 and friends).
        click(hostOption(dockerHostId));
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);
        page.waitForCondition(() -> page.locator(IMAGE_SELECT + "[disabled]").count() == 0);
        openPlSelect(IMAGE_SELECT);
        page.waitForSelector(OPEN_SELECT_POPUP + " div[role='option']");
        assertThat(page.locator(OPEN_SELECT_POPUP + " div[role='option']").count())
            .as("step 6: the seeded runtime images are offered").isGreaterThan(0);
        page.locator(OPEN_SELECT_POPUP + " div[role='option']").first().click();
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);

        assertThat(page.evaluate("window.__cfNoReloadMarker"))
            .as("every narrowing step must be client-side")
            .isEqualTo("alive");

        // 7. Submit: the record carries the kind, the narrowed host and the image.
        type("input[name='name']", "cf-first-workspace");
        page.evaluate("document.querySelector('form.cms-form-layout').requestSubmit()");
        page.waitForCondition(() -> findInstance("cf-first-workspace") != null);

        Row saved = findInstance("cf-first-workspace");
        assertThat((Object) saved.get(InstanceModel.KIND))
            .as("step 7: the card's kind persisted").isEqualTo("hohenheim:workspace");
        assertThat((Object) saved.get(InstanceModel.SERVER_ID))
            .as("step 7: the narrowed host persisted").isEqualTo(dockerHostId);
        assertThat((Object) saved.get(InstanceModel.RUNTIME_IMAGE_ID))
            .as("step 7: the chosen runtime image persisted").isNotNull();
    }

    /**
     * FALSIFICATION: a hand-posted host the picker would never offer is refused by the
     * server-side re-narrowing (the client filter is never the gate).
     */
    @Test
    @Order(2)
    void handPostedOutOfScopeHostIsRefused() throws Exception {
        HttpResponse<String> refused = httpPostForm("/admin/instances/new",
            "name=cf-forged-vm&kind=hohenheim%3Avm&server_id=" + dockerHostId,
            sessionToken, csrfToken);
        assertThat(refused.statusCode())
            .as("a vm on a docker host must not create-redirect")
            .isNotIn(302, 303);
        assertThat(findInstance("cf-forged-vm"))
            .as("and no record was persisted").isNull();

        // The control: the same post with the matching host passes coercion.
        HttpResponse<String> accepted = httpPostForm("/admin/instances/new",
            "name=cf-honest-vm&kind=hohenheim%3Avm&server_id=" + incusHostId,
            sessionToken, csrfToken);
        assertThat(accepted.statusCode())
            .as("the control: an incus host carries a vm").isIn(302, 303);
        Row saved = findInstance("cf-honest-vm");
        assertThat(saved).isNotNull();
        assertThat((Object) saved.get(InstanceModel.SERVER_ID)).isEqualTo(incusHostId);
    }

    /**
     * The advanced section: a fold is a DISPLAY state, never a payload filter.
     *
     * Steps 1-4 are the contract (rendered collapsed, inputs present, an untouched
     * create keeps the declared default, a folded field a person DID set persists);
     * step 5 is the falsification -- a refusal about a folded field forces the section
     * open, because a refusal nobody can see reads as a save that silently did nothing.
     */
    @Test
    @Order(3)
    void theAdvancedSectionFoldsWithoutFilteringTheSubmit() throws Exception {
        String form = adminGet("/admin/instances/new").body();

        // 1. The section renders, folded on first paint.
        assertThat(form).as("step 1: the create form declares its advanced section")
            .contains("data-section=\"advanced\"");
        assertThat(form).as("step 1: and it starts folded")
            .contains("data-collapsed=\"true\"");

        // 2. Folded is not absent: both members are in the DOM with their names, which
        //    is the whole difference between a disclosure and visibleIn.
        assertThat(form).as("step 2: the folded crash policy is in the DOM")
            .contains("name=\"crash_policy\"");
        assertThat(form).as("step 2: so is the folded backup target")
            .contains("name=\"backup_target_id\"");

        // 3. A create touching only the visible fields lands on the declared default.
        HttpResponse<String> plain = httpPostForm("/admin/instances/new",
            "name=cf-visible-only&kind=hohenheim%3Adocker_container&server_id=" + dockerHostId,
            sessionToken, csrfToken);
        assertThat(plain.statusCode()).as("step 3: the create redirects").isIn(302, 303);
        Row visibleOnly = findInstance("cf-visible-only");
        assertThat(visibleOnly).as("step 3: the record exists").isNotNull();
        assertThat((Object) visibleOnly.get(InstanceModel.CRASH_POLICY))
            .as("step 3: an untouched folded field keeps its declared default")
            .isEqualTo(InstanceModel.CRASH_NONE);

        // 4. And a create that DID open the fold persists what was typed there.
        HttpResponse<String> folded = httpPostForm("/admin/instances/new",
            "name=cf-advanced-set&kind=hohenheim%3Adocker_container&server_id=" + dockerHostId
                + "&crash_policy=" + InstanceModel.CRASH_RESTART,
            sessionToken, csrfToken);
        assertThat(folded.statusCode()).as("step 4: the create redirects").isIn(302, 303);
        assertThat((Object) findInstance("cf-advanced-set").get(InstanceModel.CRASH_POLICY))
            .as("step 4: a folded input still posts, and still coerces")
            .isEqualTo(InstanceModel.CRASH_RESTART);

        // 5. FALSIFICATION: a refusal about a folded field re-renders the section OPEN.
        HttpResponse<String> refused = httpPostForm("/admin/instances/new",
            "name=cf-bad-policy&kind=hohenheim%3Adocker_container&server_id=" + dockerHostId
                + "&crash_policy=not-a-declared-policy",
            sessionToken, csrfToken);
        assertThat(refused.statusCode()).as("step 5: a refusal re-renders, never redirects")
            .isNotIn(302, 303);
        assertThat(findInstance("cf-bad-policy")).as("step 5: and nothing persisted").isNull();
        assertThat(refused.body()).as("step 5: the section that holds the refusal is open")
            .contains("data-section=\"advanced\"")
            .contains("data-collapsed=\"false\"");
    }

    private static Row findInstance(String name) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.NAME.eq(name))
            .where(InstanceModel.DELETED_AT.isNull())
            .first();
    }
}
