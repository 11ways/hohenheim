package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.render.inline.InlineEditResult;
import be.elevenways.zenit.cms.common.render.inline.InlineEditState;
import be.elevenways.zenit.cms.common.render.inline.InlineEditSubmit;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.forms.common.choose.InlineCreateResult;
import be.elevenways.zenit.server.http.ReturnTarget;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DNS record list surfaces: the three write lanes (full form, quick-add bar,
 * inline cell) all funnelling through the record codec and the zone serial bump,
 * the zone's Records tab rendering the resource's own declarations, and what a
 * delegated tenant may reach on each lane.
 */
class DnsListEditingTest extends HohenheimTestBase {

    private static final String RECORD_SOURCE = "/zf/source/hohenheim.dns_record/create";

    /**
     * ONE walk down all three write lanes. Every lane submits the SAME invalid record
     * first: a lane that persisted it would be a lane that skipped
     * {@code DnsRecordEdits.validate}, and a lane that skipped the resource's own
     * write funnel would leave the zone serial behind (so DNS would keep serving the
     * old answer to every secondary).
     */
    @Test
    void threeWriteLanesFunnelThroughTheCodecAndBumpTheSerial() throws Exception {
        int zoneId = createZone("lanes.example");
        long serial = zoneSerial(zoneId);

        // 1. The full form refuses a value the codec cannot turn into an A record.
        adminForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=one&type=A&value=not-an-ip&ttl=300&enabled=true");
        assertThat(record(zoneId, "one")).as("step 1: the form lane refused the record").isNull();
        assertThat(zoneSerial(zoneId)).as("step 1: a refusal never bumps the serial").isEqualTo(serial);

        // 2. The same lane with a valid value writes, and the zone's serial moves so
        //    secondaries re-transfer.
        adminForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=one&type=A&value=192.0.2.1&ttl=300&enabled=true");
        assertThat(record(zoneId, "one")).as("step 2: the form lane created the record").isNotNull();
        assertThat(zoneSerial(zoneId)).as("step 2: the write bumped the serial").isGreaterThan(serial);
        serial = zoneSerial(zoneId);

        // 3. The quick-add bar rides the record source's create provider, which is the
        //    resource's own persistRow: the SAME codec refusal, on the field that caused it.
        InlineCreateResult refused = quickAdd(Map.of(
            "zone_id", zoneId, "type", "A", "name", "two", "value", "not-an-ip", "ttl", "300"));
        assertThat(refused.succeeded()).as("step 3: the bar refused the record").isFalse();
        assertThat(refused.violations()).as("step 3: the refusal names the value")
            .anyMatch(violation -> violation.path().equals("value"));
        assertThat(record(zoneId, "two")).as("step 3: nothing was written").isNull();
        assertThat(zoneSerial(zoneId)).as("step 3: nothing was announced").isEqualTo(serial);

        // 4. A valid quick add writes through the same funnel, serial bump included.
        InlineCreateResult added = quickAdd(Map.of(
            "zone_id", zoneId, "type", "A", "name", "two", "value", "192.0.2.2", "ttl", "300"));
        assertThat(added.succeeded()).as("step 4: the bar created the record: " + added.violations())
            .isTrue();
        assertThat(record(zoneId, "two")).as("step 4: the row exists").isNotNull();
        assertThat(zoneSerial(zoneId)).as("step 4: the write bumped the serial").isGreaterThan(serial);
        serial = zoneSerial(zoneId);

        // 5. The inline cell hands out the editor state: the fields the resource declared,
        //    a live concurrency token, and this principal's per-entry verdicts.
        int recordId = record(zoneId, "one").get(DnsRecordModel.ID);
        InlineEditState state = cellState("/admin/dns-records/" + recordId + "/cell-state");
        assertThat(state.fields().stream().map(field -> field.name()).toList())
            .as("step 5: the declared inline-editable entries, in declaration order")
            .containsExactly("name", "value", "ttl", "enabled");
        assertThat(state.editable()).as("step 5: an operator may write this record").isTrue();
        assertThat(state.editableFields())
            .as("step 5: and every declared entry is writable for them")
            .containsExactly("name", "value", "ttl", "enabled");
        assertThat(state.snapshot()).as("step 5: with a live concurrency token").isNotEmpty();

        // 6. The cell lane is a sibling of the form lane, never a bypass: the codec
        //    refuses the same value here.
        InlineEditResult badCell = cellSubmit(recordId,
            new InlineEditSubmit("value", "not-an-ip", state.snapshot()));
        assertThat(badCell.succeeded()).as("step 6: the cell lane refused the value").isFalse();
        assertThat(badCell.violations()).as("step 6: on the field that caused it")
            .anyMatch(violation -> violation.path().equals("value"));
        assertThat(record(zoneId, "one").get(DnsRecordModel.VALUE))
            .as("step 6: the stored value is untouched").isEqualTo("192.0.2.1");
        assertThat(zoneSerial(zoneId)).as("step 6: and nothing was announced").isEqualTo(serial);

        // 7. A valid cell commit writes exactly one column and bumps the serial.
        InlineEditResult savedValue = cellSubmit(recordId,
            new InlineEditSubmit("value", "192.0.2.9", state.snapshot()));
        assertThat(savedValue.succeeded())
            .as("step 7: the commit succeeded: " + savedValue.violations()).isTrue();
        Row updated = record(zoneId, "one");
        assertThat(updated.get(DnsRecordModel.VALUE)).as("step 7: the value moved")
            .isEqualTo("192.0.2.9");
        assertThat(((Number) updated.get(DnsRecordModel.TTL)).intValue())
            .as("step 7: and the columns the submit did not name did not").isEqualTo(300);
        assertThat(zoneSerial(zoneId)).as("step 7: the zone was re-announced").isGreaterThan(serial);
        serial = zoneSerial(zoneId);

        // 8. A TTL cell edit is a zone change too -- the resource's updateRow decides that,
        //    not the lane.
        InlineEditResult savedTtl = cellSubmit(recordId,
            new InlineEditSubmit("ttl", "60", savedValue.snapshot()));
        assertThat(savedTtl.succeeded())
            .as("step 8: the ttl commit succeeded: " + savedTtl.violations()).isTrue();
        assertThat(((Number) record(zoneId, "one").get(DnsRecordModel.TTL)).intValue())
            .as("step 8: the ttl moved").isEqualTo(60);
        assertThat(zoneSerial(zoneId)).as("step 8: the zone was re-announced").isGreaterThan(serial);

        // 9. The token that just wrote is stale, and a stale token is refused rather than
        //    silently overwriting whoever wrote in between.
        InlineEditResult stale = cellSubmit(recordId,
            new InlineEditSubmit("ttl", "120", savedValue.snapshot()));
        assertThat(stale.succeeded()).as("step 9: the stale commit was refused").isFalse();
        assertThat(stale.conflict()).as("step 9: as a CONFLICT, not a field violation").isTrue();
        assertThat(((Number) record(zoneId, "one").get(DnsRecordModel.TTL)).intValue())
            .as("step 9: a conflict writes nothing").isEqualTo(60);

        // 10. TYPE is deliberately NOT inline-editable (switching it swaps the DATA
        //     sub-schema), and an undeclared field is refused LOUDLY rather than dropped.
        InlineEditState fresh = cellState("/admin/dns-records/" + recordId + "/cell-state");
        InlineEditResult typed = cellSubmit(recordId,
            new InlineEditSubmit("type", "TXT", fresh.snapshot()));
        assertThat(typed.succeeded()).as("step 10: type is not an inline cell").isFalse();
        assertThat(record(zoneId, "one").get(DnsRecordModel.TYPE))
            .as("step 10: and the refusal wrote nothing").isEqualTo("A");
    }

    /**
     * The zone's Records tab renders the RESOURCE's declarations: its search box, its
     * quick-add bar (with this zone preset), its inline-editable cells and its typed
     * cells -- and the cells it offers agree with what the cell endpoint reports.
     */
    @Test
    void zoneRecordsTabRendersTheResourceSurfaces() throws Exception {
        int zoneId = createZone("tab.example");
        int recordId = createRecord(zoneId, "www", DnsRecordModel.TYPE_A, "192.0.2.20");
        createRecord(zoneId, "mail", DnsRecordModel.TYPE_A, "192.0.2.21");

        String path = "/admin/dns-zones/" + zoneId + "/page/records";
        HttpResponse<String> page = adminGet(path);
        assertThat(page.statusCode()).as("step 1: the tab renders").isEqualTo(200);
        String body = page.body();
        assertThat(body).as("step 1: with the framework search box, add bar and inline cells")
            .contains("<cms-list-search")
            .contains("<cms-quick-add")
            .contains("<cms-inline-cell");
        assertThat(body).as("step 1: the TYPE cell is the enum badge the model declares,"
                + " icon and colour included")
            .contains("location-dot");
        assertThat(body).as("step 1: and the value cell carries a copy chip")
            .contains("<pl-copy-button");

        // 2. The offered cells are exactly the declared inline-editable fields -- the same
        //    answer the cell endpoint gives for this record and principal (the affordance
        //    and the enforcement may never drift apart).
        InlineEditState state = cellState("/admin/dns-records/" + recordId + "/cell-state");
        for (String field : state.editableFields()) {
            assertThat(body).as("step 2: the page offers the '" + field + "' cell the endpoint allows")
                .contains("field=\"" + field + "\"");
        }

        // 3. The search box narrows the listing over the resource's declared search fields.
        String matching = adminGet(path + "?search=mail").body();
        assertThat(matching).as("step 3: the match is listed").contains("mail");
        assertThat(matching).as("step 3: and the rest is not")
            .doesNotContain("data-row-key=\"" + recordId + "\"");
        assertThat(matching).as("step 3: a narrowed listing is worth coming back to, so the"
                + " record links carry it as their return target")
            .contains("_return=");

        // 4. A term nothing matches reaches the filtered-empty state with its clear link,
        //    never the whole listing.
        String empty = adminGet(path + "?search=nothing-matches-this").body();
        assertThat(empty).as("step 4: the filtered empty state")
            .contains("data-cms-empty-state=\"filtered\"")
            .contains("data-cms-clear-filters");
    }

    /**
     * A delegated tenant on the /manage surface: authority over the RECORD decides every
     * lane, and it is decided per record rather than per zone.
     */
    @Test
    void tenantAuthorityDecidesEveryLanePerRecord() throws Exception {
        int zoneId = createZone("tenant.example");
        int ownName = createRecord(zoneId, "own", DnsRecordModel.TYPE_A, "192.0.2.30");
        int foreignName = createRecord(zoneId, "foreign", DnsRecordModel.TYPE_A, "192.0.2.31");

        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "dns-tenant@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Dns Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        int tenantId = user.get(UserModel.ID);

        // The tenant manages a site serving own.tenant.example, which is what gives it
        // hostname authority over that ONE name (and manage-panel access).
        int siteId = createSite("dns-tenant-site", "own.tenant.example");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);
        TestSession tenant = sessionFor(tenantId);

        // 1. The name it serves is editable in place: the state says so and the commit lands.
        InlineEditState own = cellState("/manage/dns-records/" + ownName + "/cell-state",
            tenant.token());
        assertThat(own.editable()).as("step 1: the served name is writable").isTrue();
        InlineEditResult saved = cellSubmit("/manage/dns-records/" + ownName + "/cell",
            new InlineEditSubmit("value", "192.0.2.40", own.snapshot()), tenant);
        assertThat(saved.succeeded()).as("step 1: and the commit lands: " + saved.violations())
            .isTrue();
        assertThat(Models.get(DnsRecordModel.class).findById(ownName).get(DnsRecordModel.VALUE))
            .as("step 1: the value moved").isEqualTo("192.0.2.40");

        // 2. A name in the same zone it does not serve is not even visible: an out-of-scope
        //    record reads as missing, never as forbidden.
        assertThat(httpGet("/manage/dns-records/" + foreignName + "/cell-state", tenant.token())
                .statusCode())
            .as("step 2: an unreachable record has no editor state").isEqualTo(404);

        // 3. With a VIEW grant the record becomes readable -- and the editor state says, in
        //    the same answer the write lane enforces, that it is still not writable.
        RecordGrants.grant(GrantSubjectType.USER, tenantId, DnsRecordModel.MODEL_ID, foreignName,
            HohenheimAccess.VIEW, true);
        InlineEditState foreign = cellState("/manage/dns-records/" + foreignName + "/cell-state",
            tenant.token());
        assertThat(foreign.editable()).as("step 3: viewing is not authoring").isFalse();
        assertThat(foreign.editableFields()).as("step 3: so no cell is offered").isEmpty();

        // 4. And the write lane refuses it outright: a denial is not a violation the operator
        //    could fix by typing something else.
        HttpResponse<String> refused = httpPostDry("/manage/dns-records/" + foreignName + "/cell",
            Zenit.DRY.stringify(new InlineEditSubmit("value", "10.0.0.1", foreign.snapshot())),
            tenant.token(), tenant.csrf());
        assertThat(refused.statusCode()).as("step 4: the commit is forbidden").isEqualTo(403);
        assertThat(Models.get(DnsRecordModel.class).findById(foreignName).get(DnsRecordModel.VALUE))
            .as("step 4: and wrote nothing").isEqualTo("192.0.2.31");

        // 5. The quick-add bar's create lane is operator-only: its form carries zone_id, so
        //    it would add into any zone at all. A tenant creates through its own form, which
        //    resolves the zone from the name it typed.
        HttpResponse<String> barred = httpPostDry(RECORD_SOURCE,
            Zenit.DRY.stringify(Map.of("zone_id", zoneId, "type", "A", "name", "sneaky",
                "value", "10.0.0.2", "ttl", "300")),
            tenant.token(), tenant.csrf());
        assertThat(barred.statusCode()).as("step 5: the tenant is refused the bar's create lane")
            .isEqualTo(403);
        assertThat(record(zoneId, "sneaky")).as("step 5: and nothing was written").isNull();
    }

    /**
     * The dyndns credential lifecycle THROUGH the admin UI, not just through the service.
     *
     * The zone's Records tab is a BESPOKE page rendering the framework's shared row partial,
     * whose overflow items are portalled out of the table and reach their form by {@code form=}
     * association. A page that does not carry that form turns every invoke row action into an
     * inert button: the menu closes and nothing is sent. So this walks the affordance, its
     * association, the invoke, and the one-time disclosure the toast is.
     */
    @Test
    void dyndnsTokenMintsFromTheZoneRecordsTabAndDisclosesTheTokenOnce() throws Exception {
        int zoneId = createZone("dyndns-tab.example");
        int recordId = createRecord(zoneId, "home", DnsRecordModel.TYPE_A, "192.0.2.50");

        String tab = "/admin/dns-zones/" + zoneId + "/page/records";
        String body = adminGet(tab).body();

        // 1. The action is offered on the row (address record + the dyndns capability).
        String item = buttonCarrying(body, "dyndns_token");
        assertThat(item).as("step 1: the tab offers the dyndns token action").isNotNull();

        // 2. It submits through a form that EXISTS on this page. A form= naming nothing is
        //    the whole bug: the button is then associated with no form and clicking it is
        //    a no-op the browser reports nowhere.
        String formId = attributeOf(item, "form");
        assertThat(formId).as("step 2: the menu item is form-associated").isNotEmpty();
        assertThat(body).as("step 2: and the page renders that form")
            .contains("id=\"" + formId + "\"");
        assertThat(body).as("step 2: carrying the CSRF field its POST needs")
            .contains("name=\"csrf_token\"");

        // 3. Invoking the rendered target mints the credential.
        String target = attributeOf(item, "formaction");
        assertThat(DynamicDnsService.credentialFor(recordId))
            .as("step 3: the record is not dynamic yet").isNull();
        HttpResponse<String> invoked = httpPostForm(target, "", sessionToken, csrfToken);
        String back = invoked.headers().firstValue("Location").orElse(null);
        assertThat(back).as("step 3: the invoke answered with a redirect back").isNotNull();
        assertThat(DynamicDnsService.credentialFor(recordId))
            .as("step 3: and the record is now dynamic").isNotNull();

        // 4. The plaintext is disclosed EXACTLY once: only the digest is at rest, so the
        //    toast is the only copy the operator will ever see.
        assertThat(adminGet(path(back)).body())
            .as("step 4: the minted token is shown once").contains("hdyn_");
        assertThat(adminGet(path(back)).body())
            .as("step 4: and a reload never shows it again").doesNotContain("hdyn_");
    }

    /**
     * A delete confirmed from the zone's Records tab lands back on THAT tab. The
     * framework's own fallback after a dns-record write is the global record list, so the
     * tab must bind itself as the return target on every outgoing link, search or not.
     */
    @Test
    void deletingFromTheZoneRecordsTabReturnsToTheTab() throws Exception {
        int zoneId = createZone("delete-return.example");
        int recordId = createRecord(zoneId, "gone", DnsRecordModel.TYPE_A, "192.0.2.51");

        String tab = "/admin/dns-zones/" + zoneId + "/page/records";
        String body = adminGet(tab).body();

        // 1. The row's delete carrier names the tab as where to come back to.
        String item = buttonCarrying(body, "/admin/dns-records/" + recordId + "/delete");
        assertThat(item).as("step 1: the tab offers the delete").isNotNull();
        String target = attributeOf(item, "formaction");
        assertThat(target)
            .as("step 1: the delete carries this tab as its return target")
            .contains(ReturnTarget.PARAM + "=")
            .contains(URLEncoder.encode(tab, StandardCharsets.UTF_8));

        // 2. Confirming it deletes the record and answers with the tab, not the global list.
        HttpResponse<String> deleted = httpPostForm(target, confirmed(""), sessionToken, csrfToken);
        assertThat(deleted.statusCode()).as("step 2: the delete redirects").isIn(302, 303);
        assertThat(Models.get(DnsRecordModel.class).findById(recordId))
            .as("step 2: the record is gone").isNull();
        String back = deleted.headers().firstValue("Location").orElse("");
        assertThat(path(back))
            .as("step 2: and the operator is back on the zone's Records tab")
            .isEqualTo(tab);

        // 3. The same journey IN THE BROWSER, at the 1024px window a live check used: the
        //    card sits under the compact floor, so the delete is the compact lane's
        //    PORTALLED copy; the operator arrives by SOFT navigation carrying the
        //    quick-add's sticky pick, the way someone who just quick-added a record does;
        //    and the table they then LOOK AT no longer carries the row. A soft POST never
        //    moves the URL, so the table itself is the completion signal, never the URL.
        int second = createRecord(zoneId, "gone-too", DnsRecordModel.TYPE_A, "192.0.2.52");
        String row = "pl-table-row[data-row-key='" + second + "']";
        // The narrow window is this step's own; restore it so no later test in the class
        // inherits a 1024px viewport it never asked for.
        ViewportSize previousViewport = page.viewportSize();
        page.setViewportSize(1024, 768);
        try {
            navigateToApp("/admin/dns-zones/" + zoneId);
            waitForHydration();
            page.evaluate("t => { const a = document.createElement('a'); a.id = 'to-records-tab';"
                + " a.href = t + '?qa.type=A'; a.textContent = 'records'; document.body.appendChild(a); }",
                tab);
            click("#to-records-tab");
            page.waitForCondition(() -> page.url().contains("qa.type=A"));
            page.waitForSelector(row);
            page.locator(row + " .cms-row-action-compact pl-dropdown-menu-trigger")
                .first().evaluate("el => el.click()");
            String popup = "he-bottom .pl-dropdown-menu-content__popup:visible ";
            String deleteItem = popup + "button.cms-menu-action[data-cms-lane='compact']"
                + "[formaction^='/admin/dns-records/" + second + "/delete?']";
            page.waitForSelector(deleteItem);
            click(deleteItem);
            assertIsVisible(".pl-alertdialog-modal[data-open]");
            click("[data-cms-confirm-ok]");
            page.waitForCondition(() -> Models.get(DnsRecordModel.class).findById(second) == null);
            page.waitForCondition(() -> page.locator(row).count() == 0);
            assertThat(page.url())
                .as("step 3: the browser stayed on the zone's Records tab, sticky pick included")
                .contains(tab + "?qa.type=A");
            assertThat(page.locator(row).count())
                .as("step 3: the table the operator looks at no longer carries the deleted row")
                .isZero();
            assertThat(page.locator("pl-table-row[data-row-key='" + recordId + "']").count())
                .as("step 3: and the redirect's render is the tab, not the global list")
                .isZero();
        } finally {
            if (previousViewport != null) {
                page.setViewportSize(previousViewport.width, previousViewport.height);
            }
        }
    }

    /**
     * The tab joins the framework's COMPACT row-action register: below the 48rem floor the
     * split lane and the ordinary menu leave the layout and one compact menu carries the
     * whole action set -- still submitting through this page's own carrier form.
     *
     * AIDEV-NOTE: the register is a {@code @container} query off {@code .cms-list-card}'s
     * inline size, so a bespoke page participates by SITTING IN THAT CARD and nothing else;
     * the row markup is already the shared partial. Driven in the browser because the
     * served HTML is identical either way -- only the resolved CSS differs.
     */
    @Test
    void narrowRecordsTabCollapsesItsRowActionsIntoTheCompactMenu() {
        int zoneId = createZone("compact-tab.example");
        int recordId = createRecord(zoneId, "home", DnsRecordModel.TYPE_A, "192.0.2.80");
        String tab = "/admin/dns-zones/" + zoneId + "/page/records";
        String row = "pl-table-row[data-row-key='" + recordId + "'] ";

        // 1. A narrow window leaves the card under its floor: the lane's Edit and the
        //    ordinary menu trigger are out of the layout, the compact trigger is in.
        page.setViewportSize(760, 844);
        navigateToApp(tab);
        waitForHydration();
        assertIsNotVisible(row + ".cms-row-action-lane pl-button[data-action-id='zenitcms:edit']");
        assertIsNotVisible(row + ".cms-row-action-more pl-button");
        assertIsVisible(row + ".cms-row-action-compact pl-button");

        // 2. That one menu carries BOTH halves: the lane's Edit as a menu link and the
        //    overflow's dyndns mint as a submitter associated with THIS page's form.
        String popup = "he-bottom .pl-dropdown-menu-content__popup:visible ";
        String compactEdit = popup + "a.cms-menu-action[data-cms-lane='compact']"
            + "[data-action-id='zenitcms:edit']";
        String compactMint = popup + "button.cms-menu-action[data-cms-lane='compact']"
            + "[formaction^='/admin/dns-records/" + recordId + "/action/dyndns_token?']";
        page.locator(row + ".cms-row-action-compact pl-dropdown-menu-trigger")
            .first().evaluate("el => el.click()");
        page.waitForSelector(compactEdit);
        assertIsVisible(compactMint);
        assertThat(getAttribute(compactMint, "form"))
            .as("step 2: the compact copy submits through the tab's own carrier form")
            .isEqualTo("dns-records-list-form");

        // 3. And it INVOKES: a register that only looked right would leave the operator
        //    with the sole reachable copy of every action dead.
        assertThat(DynamicDnsService.credentialFor(recordId))
            .as("step 3: the record is not dynamic yet").isNull();
        click(compactMint);
        page.waitForCondition(() -> DynamicDnsService.credentialFor(recordId) != null);

        // 4. FALSIFICATION: on a wide window the very same page shows the lane again and
        //    the compact trigger is gone -- so step 1 measured the register, not a
        //    permanently hidden lane.
        page.setViewportSize(1400, 900);
        navigateToApp(tab);
        waitForHydration();
        assertIsVisible(row + ".cms-row-action-lane pl-button[data-action-id='zenitcms:edit']");
        assertIsNotVisible(row + ".cms-row-action-compact pl-button");
    }

    /**
     * The tab's table sits in a REAL scrollport, and it is the framework's own
     * (plumage's pl-scroll-area, the element the generated list renders): a bare div with
     * the same class scrolls nothing since the hand-written overflow CSS was retired, and
     * says nothing either -- the pinned actions column reads the scroll area's published
     * data-overflow-inline-end to know whether a column is passing under it.
     */
    @Test
    void recordsTabScrollsItsWideTableInsideTheFrameworkScrollArea() {
        int zoneId = createZone("scroll-tab.example");
        // Values wide enough to push the table past a narrow card at any font size.
        int recordId = createRecord(zoneId, "a-deliberately-long-record-name", DnsRecordModel.TYPE_TXT,
            "v=spf1 include:_spf.example include:_spf.other include:_spf.third -all");
        createRecord(zoneId, "another-deliberately-long-name", DnsRecordModel.TYPE_TXT,
            "v=DMARC1; p=reject; rua=mailto:dmarc@scroll-tab.example; pct=100; adkim=s");

        String scroller = "pl-scroll-area.cms-table-scroll";
        String viewport = scroller + " > .viewport";

        // 1. The scroll container IS the component, and there is no bare div left claiming
        //    the class the framework's stylesheet only bounds through the component's knob.
        page.setViewportSize(760, 844);
        navigateToApp("/admin/dns-zones/" + zoneId + "/page/records");
        waitForHydration();
        assertCount(scroller, 1);
        assertCount("div.cms-table-scroll", 0);

        // 2. It really is a scrollport: the table is wider than the box that holds it, and
        //    the box scrolls rather than clips.
        assertThat((Boolean) page.locator(viewport)
                .evaluate("el => el.scrollWidth > el.clientWidth + 1"))
            .as("step 2: the table overflows the scrollport it now has").isTrue();

        // 3. So the component publishes the overflow-end marker -- the half of plumage's
        //    pinned-column rule that lives on the scroll area. The other half is the cell's
        //    own opt-in, and the shadow only renders when BOTH hold.
        waitForAttribute(scroller, "data-overflow-inline-end", "");
        assertThat(getAttribute("pl-table-row[data-row-key='" + recordId + "']"
                + " pl-table-cell.cms-resource-list-row-actions", "data-sticky"))
            .as("step 3: and the actions column opts into being pinned").isEqualTo("end");

        // 4. FALSIFICATION: the marker is live state, not a stamp. Scrolling to the trailing
        //    edge retires it and raises its leading-edge twin -- an always-on attribute (or a
        //    box that never scrolled) would keep step 3 green while showing the shadow over
        //    nothing.
        page.locator(viewport).evaluate("el => el.scrollLeft = el.scrollWidth");
        waitForAttribute(scroller, "data-overflow-inline-start", "");
        page.waitForCondition(() ->
            page.locator(scroller).getAttribute("data-overflow-inline-end") == null);
    }

    /**
     * Importing a zone file REPLACES every operator-managed record, so it confirms -- but an
     * EMPTY paste is not a destructive act to be confirmed, it is an incomplete form to be
     * refused. Validation must therefore precede confirmation on both halves of the lane.
     */
    @Test
    void zoneFileImportValidatesBeforeItConfirms() throws Exception {
        int zoneId = createZone("import-empty.example");
        createRecord(zoneId, "keep", DnsRecordModel.TYPE_A, "192.0.2.60");

        String tab = "/admin/dns-zones/" + zoneId + "/page/zonefile";
        String body = adminGet(tab).body();

        // 1. The paste control is REQUIRED, which is what stops the submit event -- and with
        //    it the confirm directive bound to that event -- from ever firing on an empty form.
        String textarea = tagCarrying(body, "<textarea", "zone_text");
        assertThat(textarea).as("step 1: the zone-file textarea renders").isNotNull();
        assertThat(textarea).as("step 1: and declares the constraint that gates the submit")
            .contains("required");

        // 2. The refusal is our own copy, in the field, not only a browser bubble.
        assertThat(body).as("step 2: the field carries an error slot").contains("<pl-field-error");

        // 3. The server half refuses the same thing, so a client that bypasses the form
        //    still cannot wipe the zone with an empty paste.
        HttpResponse<String> imported = httpPostForm(
            "/admin/dns-zones/" + zoneId + "/zonefile", "zone_text=", sessionToken, csrfToken);
        assertThat(imported.headers().firstValue("Location"))
            .as("step 3: the empty import went back to the tab").isPresent();
        assertThat(record(zoneId, "keep"))
            .as("step 3: and replaced nothing").isNotNull();
    }

    /**
     * A record with no explicit TTL is not a record without a TTL: it serves the ZONE's
     * default. The list used to say "None", which reads as "this record has no TTL" -- the
     * one thing a DNS operator must never be told wrongly.
     */
    @Test
    void ttlCellNamesTheInheritedZoneDefaultInsteadOfNone() throws Exception {
        int zoneId = createZone("ttl.example");
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        assertThat(DnsZoneModel.defaultTtlOf(zone))
            .as("step 0: the fixture zone serves the declared zone default").isEqualTo(3600);

        int inheriting = createRecordWithoutTtl(zoneId, "bare", DnsRecordModel.TYPE_A, "192.0.2.70");
        createRecord(zoneId, "explicit", DnsRecordModel.TYPE_A, "192.0.2.71");

        // 1. The zone's Records tab names the effective value, derived from the field's own
        //    declared default (3600) rather than a literal spelled in the cell.
        String tab = adminGet("/admin/dns-zones/" + zoneId + "/page/records").body();
        assertThat(tab).as("step 1: the inherited TTL is named, with its number")
            .contains("Zone default (3600)");

        // 2. The generated list is the same surface answer: the resource decides, not the page.
        assertThat(adminGet("/admin/dns-records?filter.name=bare").body())
            .as("step 2: the generated list agrees").contains("Zone default (3600)");

        // 3. FALSIFICATION: the number is the ZONE's, not a constant. Retune the zone and the
        //    cell follows; a hardcoded 3600 would keep lying here.
        zone.set(DnsZoneModel.DEFAULT_TTL, 120);
        Models.get(DnsZoneModel.class).save(zone);
        String retuned = adminGet("/admin/dns-zones/" + zoneId + "/page/records").body();
        assertThat(retuned).as("step 3: the cell follows the zone").contains("Zone default (120)");
        assertThat(retuned).as("step 3: and no longer claims the declared default")
            .doesNotContain("Zone default (3600)");

        // 4. Giving the record its own TTL takes it out of the inherited branch entirely.
        Row record = Models.get(DnsRecordModel.class).findById(inheriting);
        record.set(DnsRecordModel.TTL, 60);
        Models.get(DnsRecordModel.class).save(record);
        assertThat(adminGet("/admin/dns-zones/" + zoneId + "/page/records").body())
            .as("step 4: an explicit TTL is never described as inherited")
            .doesNotContain("Zone default");
    }

    // --- html probes ------------------------------------------------------------------

    /** @return the opening {@code <button>} tag carrying the marker, or null */
    private static String buttonCarrying(String html, String marker) {
        return tagCarrying(html, "<button", marker);
    }

    /** @return the first opening tag of the given name that carries the marker, or null */
    private static String tagCarrying(String html, String open, String marker) {
        int from = 0;
        while (true) {
            int start = html.indexOf(open, from);
            if (start < 0) {
                return null;
            }
            int end = html.indexOf('>', start);
            if (end < 0) {
                return null;
            }
            String tag = html.substring(start, end + 1);
            if (tag.contains(marker)) {
                return tag;
            }
            from = end + 1;
        }
    }

    /** @return the attribute's value with entities decoded, or "" when the tag has none */
    private static String attributeOf(String tag, String name) {
        String needle = " " + name + "=\"";
        int at = tag.indexOf(needle);
        if (at < 0) {
            return "";
        }
        int start = at + needle.length();
        int end = tag.indexOf('"', start);
        return end < 0 ? "" : tag.substring(start, end).replace("&amp;", "&");
    }

    /** @return a Location header reduced to the path (+query) the test helpers take */
    private static String path(String location) {
        int scheme = location.indexOf("://");
        if (scheme < 0) {
            return location;
        }
        int slash = location.indexOf('/', scheme + 3);
        return slash < 0 ? "/" : location.substring(slash);
    }

    // --- transport --------------------------------------------------------------------

    private void adminForm(String path, String body) throws Exception {
        httpPostForm(path, body, sessionToken, csrfToken);
    }

    private InlineCreateResult quickAdd(Map<String, Object> values) throws Exception {
        HttpResponse<String> response = httpPostDry(RECORD_SOURCE,
            Zenit.DRY.stringify(values), sessionToken, csrfToken);
        assertThat(response.statusCode()).as("the inline-create lane answered").isEqualTo(200);
        return (InlineCreateResult) Zenit.DRY.parse(response.body());
    }

    private InlineEditState cellState(String path) throws Exception {
        return cellState(path, sessionToken);
    }

    private InlineEditState cellState(String path, String session) throws Exception {
        HttpResponse<String> response = httpGet(path, session);
        assertThat(response.statusCode()).as("the cell state answered").isEqualTo(200);
        return (InlineEditState) Zenit.DRY.parse(response.body());
    }

    private InlineEditResult cellSubmit(int recordId, InlineEditSubmit submit) throws Exception {
        return cellSubmit("/admin/dns-records/" + recordId + "/cell", submit,
            new TestSession(sessionToken, csrfToken));
    }

    private InlineEditResult cellSubmit(String path, InlineEditSubmit submit, TestSession session)
            throws Exception {
        HttpResponse<String> response = httpPostDry(path, Zenit.DRY.stringify(submit),
            session.token(), session.csrf());
        assertThat(response.statusCode()).as("the cell lane answered").isEqualTo(200);
        return (InlineEditResult) Zenit.DRY.parse(response.body());
    }

    // --- fixtures ---------------------------------------------------------------------

    /** A primary zone with no peer: what every list-editing journey here edits. */
    private static int createZone(String origin) {
        return DnsFixtures.createZone(origin, DnsZoneModel.ROLE_PRIMARY, null);
    }

    /** A record that inherits the zone's default TTL: the shape the TTL cell must describe. */
    private static int createRecordWithoutTtl(int zoneId, String name, String type, String value) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row record = records.createEmptyRow();
        record.set(DnsRecordModel.ZONE_ID, zoneId);
        record.set(DnsRecordModel.NAME, name);
        record.set(DnsRecordModel.TYPE, type);
        record.set(DnsRecordModel.VALUE, value);
        record.set(DnsRecordModel.ENABLED, true);
        records.save(record);
        return record.get(DnsRecordModel.ID);
    }

    private static int createRecord(int zoneId, String name, String type, String value) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row record = records.createEmptyRow();
        record.set(DnsRecordModel.ZONE_ID, zoneId);
        record.set(DnsRecordModel.NAME, name);
        record.set(DnsRecordModel.TYPE, type);
        record.set(DnsRecordModel.VALUE, value);
        record.set(DnsRecordModel.TTL, 300);
        record.set(DnsRecordModel.ENABLED, true);
        records.save(record);
        return record.get(DnsRecordModel.ID);
    }

    private static int createSite(String slug, String hostname) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, slug);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + slug));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        int siteId = site.get(SiteModel.ID);

        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        Models.get(SiteDomainModel.class).save(domain);
        return siteId;
    }

    private static Row record(int zoneId, String name) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq(name))
            .first();
    }

    private static long zoneSerial(int zoneId) {
        Integer serial = Models.get(DnsZoneModel.class).findById(zoneId).get(DnsZoneModel.SERIAL);
        return serial != null ? serial : 0;
    }
}
