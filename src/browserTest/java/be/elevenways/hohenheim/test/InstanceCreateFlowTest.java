package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.zenit.cms.server.panel.PartsWrites;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The instance CREATE journey of the admin UI: choice cards decide the kind, the host pick JUDGES every host by it and
 * the runtime-image pick narrows from it client-side, the submit persists the matching record -- and the server
 * refuses a hand-posted host the kind cannot be placed on, in the placement gate's own words.
 */
class InstanceCreateFlowTest extends HohenheimTestBase {

    private static final String OPEN_SELECT_POPUP = "he-bottom .pl-select-popup[data-open]";
    private static final String HOST_SELECT = "pl-select[name='server_id']";
    private static final String IMAGE_SELECT = "pl-select[name='runtime_image_id']";

    private static Integer dockerHostId;
    private static Integer incusHostId;

    @BeforeAll
    static void seedHosts() {
        // Both admitted and measured, so what the pick says about them is the KIND's verdict: the docker host's data
        // root enforces quotas, the incus host's does not.
        dockerHostId = seeded("cf-docker-host", () -> HostFixtures.admittedDockerHost("cf-docker-host", 16384));
        VolumeBackends.store(Models.get(ServerModel.class).findById(dockerHostId),
            new VolumeBackends.Detection(VolumeBackend.BTRFS, "/fixture/volumes", "fixture: btrfs"));
        incusHostId = seeded("cf-incus-host", () -> HostFixtures.admittedIncusHost("cf-incus-host"));
        VolumeBackends.store(Models.get(ServerModel.class).findById(incusHostId),
            new VolumeBackends.Detection(VolumeBackend.NONE, "/fixture/volumes", "fixture: ext4"));
    }

    /** @return the host of that name this database already holds, else the one the fixture creates */
    private static Integer seeded(String name, IntSupplier create) {
        Row existing = Models.get(ServerModel.class).findByName(name);
        return existing != null ? existing.get(ServerModel.ID) : create.getAsInt();
    }

    /** Asserts the open host pick offers this host disabled, saying why in words that contain {@code words}. */
    private void assertJudged(Integer hostId, String words, String message) {
        page.waitForCondition(() -> "true".equals(page.locator(hostOption(hostId)).getAttribute("aria-disabled")));
        assertThat(page.locator(hostOption(hostId)).textContent()).as(message).contains(words);
    }

    /** Asserts the open host pick offers this host as an ordinary choice. */
    private void assertChoice(Integer hostId, String message) {
        page.waitForSelector(hostOption(hostId));
        page.waitForCondition(() -> !"true".equals(page.locator(hostOption(hostId)).getAttribute("aria-disabled")));
        assertThat(page.locator(hostOption(hostId)).getAttribute("aria-disabled")).as(message).isNotEqualTo("true");
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

    /** Whether the runtime image pick is drawn: a kind's declared fields decide, never a disabled stub. */
    private boolean imageShown() {
        return page.locator(IMAGE_SELECT + " .pl-select-field").isVisible();
    }

    /** One create-form load walked through kinds, hosts and the image pick. */
    @Test
    void kindCardsDriveTheDependentPicks() {
        navigateToApp("/admin/instances/new");
        waitForHydration();

        // 1. The kind is a CARD choice: every authorable kind, each with an icon and a
        //    one-sentence description; the generated kinds are simply not offered.
        var cards = page.locator(
            "pl-choice-group[name='kind'] pl-choice-card[data-value^='hohenheim:']");
        assertThat(cards.count()).as("step 1: the six authorable kinds are cards")
            .isEqualTo(6);
        String cardsText = cards.allTextContents().toString();
        assertThat(cardsText).as("step 1: every card explains itself")
            .contains("persistent dev box", "health-gated releases", "container image", "served as plain files");
        assertThat(cardsText).as("step 1: generated kinds are not offered")
            .doesNotContain("Release", "Database container", "Stack service");

        // 1b. The heading names what is being CREATED. A resource form page falls back to
        //     the RESOURCE label for its heading, and this resource's label is the plural
        //     the nav needs -- so this screen was headed "Instances" while it created
        //     exactly one. createTitle is the only seam the framework offers.
        assertThat(page.locator("h1").first().textContent().trim())
            .as("step 1b: the create screen names one instance, not the list")
            .isEqualTo("New instance");

        // 2. No kind chosen: every host is offered and none is judged yet, and the runtime image, which only the
        //    kinds that run inside one ask, is not shown at all.
        openPlSelect(HOST_SELECT);
        assertChoice(dockerHostId, "step 2: no kind, nothing judged: the docker host is a choice");
        assertChoice(incusHostId, "step 2: and so is the incus host");
        closeOpenPopup();
        assertThat(imageShown()).as("step 2: no runtime image before a kind asks for one").isFalse();

        // Reload marker: every judging step below must be client-side.
        page.evaluate("window.__cfNoReloadMarker = 'alive'");

        // 3. A Docker-only kind: the Incus host is offered disabled, naming the runtime it lacks.
        clickKindCard("docker_container");
        openPlSelect(HOST_SELECT);
        assertChoice(dockerHostId, "step 3: the docker host takes a Docker container");
        assertJudged(incusHostId, "runs only on", "step 3: the Incus host says it runs the wrong runtime");
        closeOpenPopup();

        // ...and the runtime image is still not shown: a raw container ignores the column, so the kind's declared
        // fields leave it out instead of offering a pick that says to leave it empty.
        assertThat(imageShown()).as("step 3: a Docker container shows no runtime image").isFalse();

        // 4. An Incus-only kind flips the verdicts around.
        clickKindCard("vm");
        openPlSelect(HOST_SELECT);
        assertChoice(incusHostId, "step 4: the incus host takes a virtual machine");
        assertJudged(dockerHostId, "runs only on", "step 4: the Docker host says it runs the wrong runtime");
        closeOpenPopup();
        assertThat(imageShown()).as("step 4: a virtual machine shows no runtime image").isFalse();

        // 5. A workspace runs on both runtimes but DEMANDS a quota-capable volume backend, so the Incus host (an
        //    ext4 data root) is offered disabled even though its runtime matches, in the kind's own words.
        clickKindCard("workspace");
        openPlSelect(HOST_SELECT);
        assertChoice(dockerHostId, "step 5: the btrfs host takes a workspace");
        assertJudged(incusHostId, "cannot enforce a volume quota",
            "step 5: the Incus host says its data root cannot enforce a volume quota");

        // 6. A workspace runs inside a runtime image: the pick is shown and resolves, and the seeded
        //    catalog answers (RuntimeImageSeeder ships node-22 and friends).
        click(hostOption(dockerHostId));
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);
        page.waitForCondition(this::imageShown);
        page.waitForCondition(() -> page.locator(IMAGE_SELECT + "[disabled]").count() == 0);
        openPlSelect(IMAGE_SELECT);
        page.waitForSelector(OPEN_SELECT_POPUP + " div[role='option']");
        assertThat(page.locator(OPEN_SELECT_POPUP + " div[role='option']").count())
            .as("step 6: the seeded runtime images are offered").isGreaterThan(0);
        page.locator(OPEN_SELECT_POPUP + " div[role='option']").first().click();
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);

        assertThat(page.evaluate("window.__cfNoReloadMarker"))
            .as("every judging step must be client-side")
            .isEqualTo("alive");

        // 7. Submit: the record carries the kind, the narrowed host and the image.
        type("input[name='name']", "cf-first-workspace");
        page.evaluate("document.querySelector('form.cms-form-layout').requestSubmit()");
        page.waitForCondition(() -> findInstance("cf-first-workspace") != null);

        Row saved = findInstance("cf-first-workspace");
        assertThat((Object) saved.get(InstanceModel.KIND))
            .as("step 7: the card's kind persisted").isEqualTo("hohenheim:workspace");
        assertThat((Object) saved.get(InstanceModel.SERVER_ID))
            .as("step 7: the chosen host persisted").isEqualTo(dockerHostId);
        assertThat((Object) saved.get(InstanceModel.RUNTIME_IMAGE_ID))
            .as("step 7: the chosen runtime image persisted").isNotNull();
    }

    /**
     * FALSIFICATION: a hand-posted host the kind cannot be placed on is refused by the server's own judgement, in the
     * runtime rule's words (the drawn disabled option is never the gate).
     */
    @Test
    void handPostedOutOfScopeHostIsRefused() throws Exception {
        HttpResponse<String> refused = httpPostForm("/admin/instances/new",
            "name=cf-forged-vm&kind=hohenheim%3Avm&server_id=" + dockerHostId,
            sessionToken, csrfToken);
        assertThat(refused.statusCode())
            .as("a vm on a docker host must not create-redirect")
            .isNotIn(302, 303);
        assertThat(refused.body()).as("and says why").contains("runs only on");
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
     * A host the chosen kind cannot be placed on is OFFERED, disabled, in the placement gate's own words, the way the
     * restore dialog and the migrate page offer theirs, and a hand-posted choice of it is refused in those words.
     *
     * It used to be offered as an ordinary choice: a workspace saved onto a VM-isolated host, and only the record's
     * page then said "Cannot start yet".
     */
    @Test
    void aHostTheKindCannotBePlacedOnIsOfferedDisabledAndRefusedInTheGatesWords() throws Exception {
        int vmOnly = admittedQuotaHost("cf-vm-only", ServerModel.POSTURE_VM_ISOLATED);
        int takesIt = admittedQuotaHost("cf-takes-workspace", null);
        String gateWords = "cf-vm-only is set to VM isolated, so it takes no container app";

        navigateToApp("/admin/instances/new");
        waitForHydration();
        page.evaluate("window.__cfNoReloadMarker = 'alive'");

        // 1. A workspace is a container: the VM-isolated host is offered DISABLED, its reason drawn under its name,
        //    and the host that takes it is an ordinary choice.
        clickKindCard("workspace");
        openPlSelect(HOST_SELECT);
        page.waitForSelector(hostOption(vmOnly));
        assertThat(page.locator(hostOption(vmOnly)).getAttribute("aria-disabled"))
            .as("step 1: the VM-isolated host is offered disabled").isEqualTo("true");
        assertThat(page.locator(hostOption(vmOnly)).textContent())
            .as("step 1: and says why, in the placement gate's words").contains(gateWords);
        assertThat(page.locator(hostOption(takesIt)).getAttribute("aria-disabled"))
            .as("step 1: the host that takes a workspace is a choice").isNotEqualTo("true");
        closeOpenPopup();

        // 2. A virtual machine is what that host takes: changing the kind enables it, without a round-trip.
        clickKindCard("vm");
        openPlSelect(HOST_SELECT);
        page.waitForCondition(() -> !"true".equals(page.locator(hostOption(vmOnly)).getAttribute("aria-disabled")));
        assertThat(page.locator(hostOption(vmOnly)).textContent())
            .as("step 2: and its reason is gone").doesNotContain(gateWords);
        closeOpenPopup();

        // 3. Back to a workspace: disabled again, in the same words.
        clickKindCard("workspace");
        openPlSelect(HOST_SELECT);
        page.waitForCondition(() -> "true".equals(page.locator(hostOption(vmOnly)).getAttribute("aria-disabled")));
        assertThat(page.locator(hostOption(vmOnly)).textContent())
            .as("step 3: the reason is back").contains(gateWords);
        closeOpenPopup();
        assertThat(page.evaluate("window.__cfNoReloadMarker"))
            .as("step 3: every judgement was client-side").isEqualTo("alive");

        // 4. Hand-posted anyway: refused in the gate's words, and nothing is written.
        Integer image = Models.get(RuntimeImageModel.class).find()
            .where(RuntimeImageModel.ENABLED.eq(true)).first().get(RuntimeImageModel.ID);
        HttpResponse<String> refused = httpPostForm("/admin/instances/new",
            "name=cf-ws-on-vm-host&kind=hohenheim%3Aworkspace&server_id=" + vmOnly + "&runtime_image_id=" + image,
            sessionToken, csrfToken);
        assertThat(refused.statusCode()).as("step 4: the save is refused, never redirected").isNotIn(302, 303);
        assertThat(refused.body()).as("step 4: in the gate's words").contains(gateWords);
        assertThat(findInstance("cf-ws-on-vm-host")).as("step 4: and no record was written").isNull();

        // 5. The host that takes a workspace saves.
        navigateToApp("/admin/instances/new");
        waitForHydration();
        clickKindCard("workspace");
        openPlSelect(HOST_SELECT);
        click(hostOption(takesIt));
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);
        page.waitForCondition(() -> page.locator(IMAGE_SELECT + "[disabled]").count() == 0);
        openPlSelect(IMAGE_SELECT);
        page.waitForSelector(OPEN_SELECT_POPUP + " div[role='option']");
        page.locator(OPEN_SELECT_POPUP + " div[role='option']").first().click();
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);
        type("input[name='name']", "cf-ws-on-taking-host");
        page.evaluate("document.querySelector('form.cms-form-layout').requestSubmit()");
        page.waitForCondition(() -> findInstance("cf-ws-on-taking-host") != null);
        assertThat((Object) findInstance("cf-ws-on-taking-host").get(InstanceModel.SERVER_ID))
            .as("step 5: the workspace landed on the host that takes it").isEqualTo(takesIt);
    }

    /** @return an admitted, measured Incus host whose data root enforces quotas, under the given posture when one */
    private static int admittedQuotaHost(String name, String posture) {
        var servers = Models.get(ServerModel.class);
        Row existing = servers.findByName(name);
        int id = existing != null ? existing.get(ServerModel.ID) : HostFixtures.admittedIncusHost(name);
        Row row = servers.findById(id);
        if (posture != null) {
            row.set(ServerModel.POSTURE, posture);
            servers.save(row);
        }
        VolumeBackends.store(servers.findById(id),
            new VolumeBackends.Detection(VolumeBackend.BTRFS, "/fixture/volumes", "fixture: btrfs"));
        return id;
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
            .as("step 3: an untouched folded field keeps its declared default: restart, as the Node original did")
            .isEqualTo(InstanceModel.CRASH_RESTART);

        // 4. And a create that DID open the fold persists what was typed there.
        HttpResponse<String> folded = httpPostForm("/admin/instances/new",
            "name=cf-advanced-set&kind=hohenheim%3Adocker_container&server_id=" + dockerHostId
                + "&crash_policy=" + InstanceModel.CRASH_NONE,
            sessionToken, csrfToken);
        assertThat(folded.statusCode()).as("step 4: the create redirects").isIn(302, 303);
        assertThat((Object) findInstance("cf-advanced-set").get(InstanceModel.CRASH_POLICY))
            .as("step 4: a folded input still posts, and still coerces: none opts out of the default")
            .isEqualTo(InstanceModel.CRASH_NONE);

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

    /**
     * The per-kind SETTINGS sub-form folds the same way the record form does -- and it is
     * the only half that COULD not, until schema-declared sections landed.
     *
     * Steps 1-3 walk the real create form (named folds, folded on first paint, their
     * inputs live in the DOM), step 4 proves a submit that never opened one still carries
     * the folded declarations, step 5 that a value typed inside one persists, and step 6
     * is the falsification: a refusal about a folded setting forces ITS section open and
     * leaves the others folded.
     */
    @Test
    void theKindSettingsFoldWithoutFilteringTheSubmit() throws Exception {
        navigateToApp("/admin/instances/new");
        waitForHydration();
        clickKindCard("workspace");

        // 1. Three named folds, each collapsed on first paint. Named, because this
        //    sub-form sits inside a record form that ends in its own "Advanced" fold.
        for (String section : List.of("build", "deployment", "runtime")) {
            waitForSelector(SETTINGS + " pl-card[data-section='" + section + "']");
            assertCount(SETTINGS + " pl-card[data-section='" + section
                + "'][data-collapsed='true']", 1, "step 1: the " + section + " fold is collapsed on first paint");
        }

        // 2. The decisions stay OUTSIDE every fold: the seven fields a person answers.
        for (String visible : List.of("repository_url", "provider_id", "repository", "branch",
                "build_command", "start_command", "container_port")) {
            assertThat(page.locator(SETTINGS + " [name='settings." + visible + "']").count())
                .as("step 2: " + visible + " renders").isGreaterThan(0);
            assertThat(page.locator(SETTINGS + " pl-card [name='settings." + visible + "']").count())
                .as("step 2: " + visible + " is not inside a fold").isZero();
        }

        // 3. Folded is not absent: pl-collapsible keeps its content MOUNTED, so a folded
        //    input is in the DOM under its own name -- the whole difference between a
        //    disclosure and hiding a field, which would make it unwritable.
        // (A workspace offers no preview fields: previews only accept applications.)
        for (String folded : List.of("memory_limit_mb", "home_quota_mb", "build_timeout")) {
            assertThat(page.locator(SETTINGS + " pl-card[data-section] [name='settings."
                + folded + "']").count())
                .as("step 3: " + folded + " is in the DOM, inside its fold").isGreaterThan(0);
        }

        // 4. Submit with only a visible field touched: the folded declarations ride along,
        //    because their inputs posted.
        type("input[name='name']", "cf-folded-defaults");
        type("input[name='settings.start_command']", "npm run dev");
        chooseHostAndImage();
        page.evaluate("document.querySelector('form.cms-form-layout').requestSubmit()");
        page.waitForCondition(() -> findInstance("cf-folded-defaults") != null);

        Map<String, Object> settings = settingsOf("cf-folded-defaults");
        assertThat(settings.get("start_command"))
            .as("step 4: the visible field persisted").isEqualTo("npm run dev");
        assertThat(String.valueOf(settings.get("shallow_clone")))
            .as("step 4: a folded boolean kept its declared default").isEqualTo("true");
        assertThat(GitSourceSchema.autoDeploys(settings))
            .as("step 4: and a new source deploys on push, auto-deploy's declared default").isTrue();

        // 5. A value typed INSIDE a fold persists like any other. Hand-posted, and on the
        //    kind whose create needs no runtime image: what is under test is the fold, and
        //    a hand-post is exactly a submit that never opened it.
        HttpResponse<String> typed = httpPostForm("/admin/instances/new",
            "name=cf-folded-typed&kind=hohenheim%3Adocker_container&server_id=" + dockerHostId
                + "&settings.memory_limit_mb=2048", sessionToken, csrfToken);
        assertThat(typed.statusCode()).as("step 5: the create redirects").isIn(302, 303);
        assertThat(String.valueOf(settingsOf("cf-folded-typed").get("memory_limit_mb")))
            .as("step 5: the folded value persisted").isEqualTo("2048");

        // 6. FALSIFICATION: a refusal about a folded setting re-renders THAT section open
        //    -- a refusal nobody can see reads as a save that silently did nothing.
        HttpResponse<String> refused = httpPostForm("/admin/instances/new",
            "name=cf-folded-refusal&kind=hohenheim%3Adocker_container&server_id=" + dockerHostId
                + "&settings.memory_limit_mb=not-a-number", sessionToken, csrfToken);
        assertThat(refused.statusCode()).as("step 6: a refusal re-renders, never redirects")
            .isNotIn(302, 303);
        assertThat(findInstance("cf-folded-refusal")).as("step 6: and nothing persisted").isNull();
        assertThat(sectionOpenState(refused.body(), "limits"))
            .as("step 6: the section holding the refusal is open").isEqualTo("false");
        assertThat(sectionOpenState(refused.body(), "ports"))
            .as("step 6: and the one that holds none stays folded").isEqualTo("true");
    }

    /** A source stored before new sources deployed on push keeps auto-deploy off through an unrelated edit-save. */
    @Test
    void aStoredSourceKeepsAutoDeployOffThroughAnEditSave() {
        // 1. A git source as M011 left one stored before auto-deploy defaulted on: auto_deploy an explicit
        //    false (proven on the legacy shape by GitSourceAutoDeployMigrationTest).
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put(GitSourceSchema.REPOSITORY_URL, "https://git.example.test/acme/cf-stored-source.git");
        stored.put(GitSourceSchema.BRANCH, "main");
        stored.put("container_port", 3000);
        stored.put(GitSourceSchema.AUTO_DEPLOY, false);
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, "cf-stored-source");
        app.set(InstanceModel.KIND, "hohenheim:application");
        app.set(InstanceModel.SERVER_ID, dockerHostId);
        app.set(InstanceModel.SETTINGS, stored);
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(app);

        // 2. Its Configuration (the record's form) saves an unrelated change: the build timeout, a plain field of the
        //    source section every git source offers (an application's form offers no build command since c6f9d973,
        //    and its branch is a provider pick, no plain input).
        navigateToApp("/admin/instances/" + app.get(InstanceModel.ID));
        waitForHydration();
        type("input[name='settings.build_timeout']", "1200");
        page.evaluate("document.querySelector('form.cms-form-layout').requestSubmit()");
        page.waitForCondition(() -> "1200".equals(String.valueOf(settingsOf("cf-stored-source")
            .get(GitSourceSchema.BUILD_TIMEOUT))));

        // 3. The form seeded the stored false, not the new default, so the save kept the source off.
        assertThat(GitSourceSchema.autoDeploys(settingsOf("cf-stored-source")))
            .as("step 3: an edit-save keeps a stored source's auto-deploy off").isFalse();
    }

    /**
     * WHAT THE PICKER SHOWS AND WHAT THE SUBMIT WRITES ARE ONE ANSWER.
     *
     * On a fresh installation no host accepts the workload -- and this create surface used to
     * write NULL for an empty pick, which every reader downstream spells "the local daemon".
     * The record was created against a machine no gate would have admitted. The create now routes through
     * {@link InstancePlacement#forActor} like every other create funnel: an empty pick is
     * the CHOOSER's decision, and when nothing is eligible it is a refusal ON THE HOST
     * FIELD rather than a row.
     */
    @Test
    void anEmptyHostPickIsPlacedByTheChooserOrRefusedOnTheHostField() throws Exception {
        var servers = Models.get(ServerModel.class);
        int local = ServerModel.localServerId();
        Row before = servers.findById(local);
        String priorAdmission = before.get(ServerModel.ADMISSION);
        Object priorCapabilities = before.get(ServerModel.CAPABILITIES);
        // Every other host this database holds is blocked for the walk, so the chooser has only the local one.
        Map<Integer, String> otherAdmissions = new LinkedHashMap<>();
        for (Row server : servers.find().all()) {
            int id = server.get(ServerModel.ID);
            if (id != local) {
                otherAdmissions.put(id, server.get(ServerModel.ADMISSION));
                server.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
                servers.save(server);
            }
        }
        try {
            // 1. The state a fresh installation is in: the local daemon is enrolled and
            //    BLOCKED, and so is every other host -- so nothing is eligible. The submit is
            //    refused and writes NOTHING; it used to create the record against the blocked
            //    local host.
            assertThat((String) before.get(ServerModel.ADMISSION))
                .as("fixture: the local daemon is blocked until an operator admits it")
                .isEqualTo(ServerModel.ADMISSION_BLOCKED);
            HttpResponse<String> nowhere = httpPostForm("/admin/instances/new",
                "name=cf-nowhere&kind=hohenheim%3Adocker_container&server_id=",
                sessionToken, csrfToken);
            assertThat(nowhere.statusCode())
                .as("step 1: an unplaceable create re-renders, never redirects")
                .isNotIn(302, 303);
            assertThat(findInstance("cf-nowhere"))
                .as("step 1: and NOTHING was persisted -- not even against the local daemon")
                .isNull();
            assertThat(nowhere.body())
                .as("step 1: the operator is told why, in the placement vocabulary")
                .contains("No admitted host currently accepts this workload");

            // 1b. Every refusal the operator can fix on the form arrives in ONE pass: an
            //     empty host AND a mistyped repository URL are both reported on the same
            //     render, and the typed URL is still in its input. It used to take two
            //     submits (the placement refusal hid the declaration one) and the URL came
            //     back blank (the field was declared secret, so a refused submit's value
            //     was thrown away with the stored-secret mask).
            HttpResponse<String> twice = httpPostForm("/admin/instances/new",
                "name=cf-two-refusals&kind=hohenheim%3Aapplication&server_id="
                    + "&settings.repository_url=not-a-url",
                sessionToken, csrfToken);
            assertThat(twice.statusCode())
                .as("step 1b: refused, re-rendered").isNotIn(302, 303);
            assertThat(twice.body())
                .as("step 1b: the host refusal is on the page")
                .contains("No admitted host currently accepts this workload");
            assertThat(twice.body())
                .as("step 1b: and so is the repository URL refusal, on the SAME render")
                .contains("This is not a git clone URL");
            assertThat(twice.body())
                .as("step 1b: the typed URL survives the refused submit")
                .contains("not-a-url");
            assertThat(findInstance("cf-two-refusals"))
                .as("step 1b: nothing persisted").isNull();

            // 2. And the refusal is PATHED ONTO THE HOST ENTRY, which is what puts the
            //    sentence beside the empty pick instead of in the form's generic error box.
            Throwable refused = catchThrowable(() -> PartsWrites.persistRow(
                PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES),
                Map.of("name", "cf-nowhere-direct", "kind", "hohenheim:docker_container"),
                adminAccessContext()));
            assertThat(refused).as("step 2: the persist lane refuses").isInstanceOf(Violations.class);
            assertThat(((Violations) refused).all())
                .as("step 2: on the host field, with the same message")
                .anyMatch(violation -> violation.fieldName().equals("server_id")
                    && violation.message().key().equals("no_placement_available"));
            assertThat(findInstance("cf-nowhere-direct"))
                .as("step 2: still nothing persisted").isNull();

            // 3. The operator admits and preflights the local daemon. The SAME submit --
            //    still an empty pick -- now lands, and it lands on the host the chooser
            //    picked rather than on a default nobody chose.
            HostFixtures.makeLocalPlaceable(32768);
            HttpResponse<String> placed = httpPostForm("/admin/instances/new",
                "name=cf-chosen&kind=hohenheim%3Adocker_container&server_id=",
                sessionToken, csrfToken);
            assertThat(placed.statusCode()).as("step 3: the create redirects").isIn(302, 303);
            Row chosen = findInstance("cf-chosen");
            assertThat(chosen).as("step 3: the record exists").isNotNull();
            assertThat((Object) chosen.get(InstanceModel.SERVER_ID))
                .as("step 3: on the one eligible host, written EXPLICITLY (never a null"
                    + " that only reads as local)")
                .isEqualTo(local);

            // 4. Naming a host the kind can be placed on is the operator's own authority, and it is honoured even
            //    where the chooser would not have gone (the local host has more headroom than the docker one).
            restoreAdmission(dockerHostId, otherAdmissions.get(dockerHostId));
            HttpResponse<String> named = httpPostForm("/admin/instances/new",
                "name=cf-named&kind=hohenheim%3Adocker_container&server_id=" + dockerHostId,
                sessionToken, csrfToken);
            assertThat(named.statusCode()).as("step 4: the create redirects").isIn(302, 303);
            assertThat((Object) findInstance("cf-named").get(InstanceModel.SERVER_ID))
                .as("step 4: the named host is what persisted")
                .isEqualTo(dockerHostId);
        } finally {
            Row after = servers.findById(local);
            after.set(ServerModel.ADMISSION, priorAdmission);
            after.set(ServerModel.CAPABILITIES, priorCapabilities);
            servers.save(after);
            otherAdmissions.forEach(this::restoreAdmission);
        }
    }

    private void restoreAdmission(int serverId, String admission) {
        Row server = Models.get(ServerModel.class).findById(serverId);
        if (server != null) {
            server.set(ServerModel.ADMISSION, admission);
            Models.get(ServerModel.class).save(server);
        }
    }

    /** The seeded admin as a production-shaped context (see {@link TestAccessContexts}). */
    private static AccessContext adminAccessContext() {
        Row user = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        assertThat(user).as("the harness seeded its admin").isNotNull();
        return TestAccessContexts.contextFor(new UserPrincipal(
            ((Integer) user.get(UserModel.ID)).longValue(), user.get(UserModel.DISPLAY_NAME)));
    }

    /** The kind settings sub-form: the one fieldset whose path is the settings field. */
    private static final String SETTINGS = "pl-fieldset[data-path='settings']";

    /** Pick the narrowed host and the first offered runtime image. */
    private void chooseHostAndImage() {
        openPlSelect(HOST_SELECT);
        click(hostOption(dockerHostId));
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);
        page.waitForCondition(() -> page.locator(IMAGE_SELECT + "[disabled]").count() == 0);
        openPlSelect(IMAGE_SELECT);
        page.waitForSelector(OPEN_SELECT_POPUP + " div[role='option']");
        page.locator(OPEN_SELECT_POPUP + " div[role='option']").first().click();
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);
    }

    /** @return the {@code data-collapsed} value the re-rendered form gave one section */
    private static String sectionOpenState(String html, String section) {
        String marker = "data-section=\"" + section + "\"";
        int at = html.indexOf(marker);
        assertThat(at).as("the form renders a '" + section + "' section").isGreaterThan(-1);
        String tail = html.substring(at, Math.min(html.length(), at + 300));
        int collapsed = tail.indexOf("data-collapsed=\"");
        assertThat(collapsed).as("that section declares its fold state").isGreaterThan(-1);
        return tail.substring(collapsed + 16, tail.indexOf('"', collapsed + 16));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> settingsOf(String name) {
        Row row = findInstance(name);
        assertThat(row).as("instance '" + name + "' exists").isNotNull();
        return (Map<String, Object>) row.get(InstanceModel.SETTINGS);
    }

    private static Row findInstance(String name) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.NAME.eq(name))
            .where(InstanceModel.DELETED_AT.isNull())
            .first();
    }
}
