package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.backup.BackupStorages;
import be.elevenways.hohenheim.server.backup.HohenheimStorages;
import be.elevenways.hohenheim.server.cms.BackupTargetParts;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.storage.LocalFolderStorageKind;
import be.elevenways.zenit.server.storage.StorageKinds;
import be.elevenways.zenit.server.storage.StorageOwner;
import be.elevenways.zenit.server.storage.StorageReadings;
import be.elevenways.zenit.storage.s3.server.S3StorageKind;
import be.elevenways.zenit.storage.s3.test.support.GarageServer;
import be.elevenways.zenit.storage.s3.test.support.S3TestServer;
import be.elevenways.zenit.storage.sftp.server.SftpStorageKind;
import be.elevenways.zenit.storage.sftp.test.support.SftpTestServer;
import be.elevenways.zenit.test.support.OutboundFixture;
import be.elevenways.zenit.test.support.PrivateNetworkBootFixture;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The storage screens of board FO128 in the browser: the administrator's list with the storages the server's settings
 * declare, a storage's page, the add form of each kind, the SFTP host-key question, the customer's list and where a
 * site chooses the storage its files are kept in.
 *
 * AIDEV-NOTE: it writes a screenshot of each screen to build/storage-screenshots, the evidence a landing compares with
 * the board's six screens (Storages-List, Storage-Page, Storage-Add-S3, Storage-Add-SFTP-Key, Storages-List-Customer,
 * Storage-Chosen).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class StorageScreensJourneyTest extends HohenheimTestBase {

    /** Relative to the gradle test worker's working directory, the project root. */
    private static final Path OUT = Paths.get("build", "storage-screenshots");

    private final List<String> made = new ArrayList<>();

    @AfterEach
    void removeStorages() {
        for (String name : this.made) {
            Models.get(BackupTargetModel.class).find().where(BackupTargetModel.NAME.eq(name)).delete();
        }
    }

    @Test
    void theStorageScreensReadAsTheBoardDrawsThem() throws Exception {
        page.setViewportSize(1440, 900);
        String tag = UUID.randomUUID().toString().substring(0, 6);
        Path folder = Files.createTempDirectory("hh-storage-screens");
        Row disk = storage("Backup disk " + tag, LocalFolderStorageKind.ID.toString(),
            Map.of(LocalFolderStorageKind.FOLDER.getName(), folder.toString()));
        StorageReadings.room(BackupStorages.of(disk));
        SftpTestServer server = SftpTestServer.shared();
        String boxFolder = server.freshFolder("screens");
        try (OutboundFixture ignored = server.route()) {
            Row box = storage("Storage box " + tag, SftpStorageKind.ID.toString(), sftp(server, boxFolder,
                server.hostKey()));
            StorageReadings.check(BackupStorages.of(box));

            // 1. The list: name, kind, where (the place, then the rest in muted words under it), added by, used by and
            //    room left; the storages of the server's settings read-only above the records, saying so.
            navigateToApp("/admin/" + HohenheimSlugs.BACKUP_TARGETS);
            waitForHydration();
            String list = page.content();
            assertThat(list).as("step 1: the records").contains("Backup disk " + tag, "Storage box " + tag)
                .contains("Folder on this server", "SFTP server").contains(folder.toString())
                .contains(SftpTestServer.HOST).contains("folder " + boxFolder);
            assertThat(list).as("step 1: the settings' storages, read-only: this server's site files, never a module's "
                + "own home").contains("From the server's settings").contains("Site files")
                .doesNotContain("/data/media")
                .doesNotContain("/data/content");
            for (String head : List.of("Kind", "Where", "Added by", "Used by", "Room left")) {
                assertThat(page.locator("pl-table-head").allTextContents().toString()).as("step 1: " + head)
                    .contains(head);
            }
            shoot("storages-list");

            // 2. A storage's page (its overview tab; the record's own address is its edit form): where it is in its
            //    kind's facts, its secret set and never shown, its room read by a check (a backup's or a Test's) as
            //    the board's bar, and what uses it.
            assertThat(StorageReadings.room(BackupStorages.of(box))).as("step 2: the SFTP server tells its room")
                .isPresent();
            navigateToApp("/admin/" + HohenheimSlugs.BACKUP_TARGETS + "/" + box.get(BackupTargetModel.ID) + "/page/"
                + RecordOverview.SLUG);
            waitForHydration();
            String shown = page.content();
            assertThat(shown).as("step 2: where").contains("Host", SftpTestServer.HOST, "Server's key");
            assertThat(shown).as("step 2: the secret is set, never shown").contains("Password", "never shown")
                .doesNotContain(SftpTestServer.PASSWORD);
            assertThat(page.locator("pl-usage-bar").count())
                .as("step 2: the room as a usage bar").isPositive();
            shoot("storage-page");

            // 3. Adding a storage: the kind's own form; the S3 one filled for the Garage test server and Tested in its
            //    foot, which runs the save's check over the form as it stands and says what it did, saving nothing.
            navigateToApp("/admin/" + HohenheimSlugs.BACKUP_TARGETS + "/new");
            waitForHydration();
            chooseKind(S3StorageKind.ID.toString());
            page.waitForSelector("pl-input[name='settings.bucket']");
            assertThat(page.content()).as("step 3: the S3 form").contains("Bucket", "Folder inside the bucket",
                "Secret key", "Bucket name in the path");
            assertThat(page.locator("cms-record-save").count()).as("step 3: one pair of buttons, at the foot")
                .isZero();
            assertThat(page.locator("pl-fieldset-legend").allTextContents()).as("step 3: the kind's settings unheaded")
                .doesNotContain("Settings");
            GarageServer garage = GarageServer.shared();
            OutboundFixture[] garageRoutes = garage.route();
            try {
                String releases = "Tadoma releases " + tag;
                fill("name", releases);
                fill("settings.address", "http://" + garage.host() + ":3900");
                fill("settings.region", garage.region());
                fill("settings.bucket", garage.bucket());
                fill("settings.prefix", "screens-" + tag);
                fill("settings.access_key", S3TestServer.ACCESS_KEY);
                fill("settings.secret_key", S3TestServer.SECRET_KEY);
                page.locator("[data-cms-form-foot-action]").first().click();
                page.waitForSelector("[data-cms-form-foot-result][data-worked]");
                assertThat(page.locator("[data-cms-form-foot-result] pl-alert[variant='success'] pl-alert-title")
                    .textContent()).as("step 3: the Test's alert, headed as drawn").isEqualTo("Reachable and writable");
                assertThat(page.locator("[data-cms-form-foot-result]").textContent()).as("step 3: what the Test did")
                    .contains("Hohenheim wrote a small file", "bucket " + garage.bucket());
                assertThat(Models.get(BackupTargetModel.class).find().where(BackupTargetModel.NAME.eq(releases))
                    .count()).as("step 3: nothing saved").isZero();
                shoot("storage-add-s3");
            } finally {
                for (int at = garageRoutes.length - 1; at >= 0; at--) {
                    garageRoutes[at].close();
                }
            }

            // 4. An SFTP server whose key is not confirmed yet: its Test asks whether it is the right server and shows
            //    the key it presented, nothing saved; the one button confirming it saves the form as it stands with
            //    that key pinned.
            navigateToApp("/admin/" + HohenheimSlugs.BACKUP_TARGETS + "/new");
            waitForHydration();
            chooseKind(SftpStorageKind.ID.toString());
            page.waitForSelector("pl-input[name='settings.host']");
            assertThat(page.locator("pl-toggle-group[name='settings.sign_in']").count())
                .as("step 4: Sign in with as segments, as the SFTP module declares").isEqualTo(1);
            String unpinned = "Unpinned " + tag;
            this.made.add(unpinned);
            fill("name", unpinned);
            fill("settings.host", SftpTestServer.HOST);
            fill("settings.folder", server.freshFolder("screens"));
            fill("settings.user", SftpTestServer.USER);
            fill("settings.password", SftpTestServer.PASSWORD);
            page.locator("[data-cms-form-foot-action]").first().click();
            page.waitForSelector("[data-zf-proposal-accept]");
            assertThat(page.locator("zf-proposal").textContent()).as("step 4: the question and the key presented")
                .contains("Is this the right server?", server.hostKey(), "This is the right server");
            assertThat(Models.get(BackupTargetModel.class).find().where(BackupTargetModel.NAME.eq(unpinned)).count())
                .as("step 4: nothing saved").isZero();
            shoot("storage-add-sftp-key");
            page.locator("[data-zf-proposal-accept]").first().click();
            page.waitForURL("**/" + RecordOverview.SLUG + "**");
            Row pinned = Objects.requireNonNull(Models.get(BackupTargetModel.class).find()
                .where(BackupTargetModel.NAME.eq(unpinned)).first(), "step 4: saved once confirmed");
            assertThat(RawValues.map(pinned.get(BackupTargetModel.SETTINGS)))
                .as("step 4: the key pinned, the typed password kept")
                .containsEntry("host_key", server.hostKey()).containsKey("password");
        }

        // 5. A customer's list: their own storages only, "You" as who added them, never the operator's.
        UUID customer = ApiSupport.user("storage-screens-" + tag + "@hohenheim.local", "Mira Voss");
        Path own = Files.createTempDirectory("hh-own-storage");
        Row mine = storage("Tadoma backups " + tag, LocalFolderStorageKind.ID.toString(),
            Map.of(LocalFolderStorageKind.FOLDER.getName(), own.toString()));
        RecordGrants.grant(GrantSubjectType.USER, customer, BackupTargetModel.MODEL_ID, mine.get(BackupTargetModel.ID),
            HohenheimCapabilities.MANAGE, true);
        navigateToAppAs("/manage/" + HohenheimSlugs.BACKUP_TARGETS, sessionFor(customer).token());
        waitForHydration();
        String theirs = page.content();
        assertThat(theirs).as("step 5: their own").contains("Tadoma backups " + tag, "You")
            .doesNotContain("Backup disk " + tag).doesNotContain("From the server's settings");
        shoot("storages-customer");

        // 6. Where a site's own files are kept: one picker of the storages the person may use.
        Row site = ProxyTestSupport.setupSite("hohenheim:static", "Tadoma downloads " + tag, "tadoma-dl-" + tag,
            new LinkedHashMap<>(Map.of("own_files", true)));
        navigateToApp("/admin/sites/" + site.get(SiteModel.ID));
        waitForHydration();
        String picker = "pl-select[name='settings.storage']";
        page.waitForSelector(picker);
        page.locator(picker + " .pl-select-field").first().click();
        page.waitForSelector("he-bottom .pl-select-popup[data-open]");
        assertThat(page.locator("he-bottom .pl-select-popup[data-open]").textContent())
            .as("step 6: the storages the operator may use").contains("Backup disk " + tag);
        shoot("storage-chosen-site");

        // 7. In Dutch: the add form and the Test's alert in the reader's words.
        page.setExtraHTTPHeaders(Map.of("Accept-Language", "nl"));
        try {
            navigateToApp("/admin/" + HohenheimSlugs.BACKUP_TARGETS + "/new");
            waitForHydration();
            chooseKind(SftpStorageKind.ID.toString());
            page.waitForSelector("pl-input[name='settings.host']");
            assertThat(page.content()).as("step 7: the form in Dutch").contains("Een opslag toevoegen");
            shoot("storage-add-nl");
        } finally {
            page.setExtraHTTPHeaders(Map.of());
        }
    }

    /**
     * A customer's own storage through the /manage twin: added (the save runs the storage's check), tested, renamed
     * and deleted; an operator's storage is not theirs to open, and one aimed at a private address is refused.
     */
    @Test
    void aCustomerAddsTestsEditsAndDeletesTheirOwnStorage() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        UUID customer = ApiSupport.user("storage-owner-" + tag + "@hohenheim.local", "Noor Haddad");
        AccessContext theirs = AccessContext.of(TenantConduits.stubIn(new UserPrincipal(customer, "user-" + customer),
            HohenheimSlugs.MANAGE));
        SftpTestServer server = SftpTestServer.shared();
        String name = "Lumen backups " + tag;
        this.made.add(name);
        this.made.add("Lumen archive " + tag);
        Object key;
        try (OutboundFixture ignored = server.route()) {
            // 1. Added through the twin: saved after its check passed, and theirs to manage.
            key = PanelResourceCalls.create(HohenheimSlugs.MANAGE, HohenheimSlugs.BACKUP_TARGETS, Map.of(
                "name", name, "kind", SftpStorageKind.ID.toString(),
                "settings", sftp(server, server.freshFolder("tenant"), server.hostKey())), theirs);
            Row saved = Objects.requireNonNull(Models.get(BackupTargetModel.class).findById(key), "step 1: saved");
            assertThat(HohenheimStorages.ownerOf(saved)).as("step 1: the customer owns it")
                .isInstanceOf(StorageOwner.Tenant.class);

            // 2. Its Test runs the storage's own check against the server; what it found is what the list says next.
            assertThat(PanelResourceCalls.invoke(HohenheimSlugs.MANAGE, HohenheimSlugs.BACKUP_TARGETS,
                BackupTargetParts.TEST.id(), key, null, theirs)).as("step 2: the Test answered").isNotNull();
            assertThat(StorageReadings.of(name).check()).as("step 2: checked").isNotNull()
                .satisfies(check -> assertThat(check.isUsable()).as(check.diagnostic()).isTrue());

            // 3. Renamed through the twin.
            PanelResourceCalls.patch(HohenheimSlugs.MANAGE, HohenheimSlugs.BACKUP_TARGETS, key,
                Map.of("name", "Lumen archive " + tag), theirs);
            assertThat((String) Objects.requireNonNull(Models.get(BackupTargetModel.class).findById(key))
                .get(BackupTargetModel.NAME)).as("step 3: renamed").isEqualTo("Lumen archive " + tag);
        }

        // 4. An operator's storage is not one the customer can open, and its address reaches no customer.
        Row operators = storage("Operator disk " + tag, LocalFolderStorageKind.ID.toString(),
            Map.of(LocalFolderStorageKind.FOLDER.getName(), Files.createTempDirectory("hh-operators").toString()));
        assertThat(PanelResourceCalls.tab(HohenheimSlugs.MANAGE, HohenheimSlugs.BACKUP_TARGETS,
            operators.get(BackupTargetModel.ID), RecordOverview.SLUG, theirs).status())
            .as("step 4: not theirs to open").isEqualTo(404);

        // 5. Aimed at a private address, a customer's storage is refused and nothing is saved.
        String inside = "Inside " + tag;
        try (PrivateNetworkBootFixture ignored = new PrivateNetworkBootFixture();
             OutboundFixture lan = OutboundFixture.routeResolved(SftpTestServer.HOST,
                 InetAddress.getByName("10.0.0.5"), server.port())) {
            PrivateNetworkBootFixture.declare(StorageKinds.PRIVATE_NETWORKS, true);
            assertThat(catchThrowable(() -> PanelResourceCalls.create(HohenheimSlugs.MANAGE,
                HohenheimSlugs.BACKUP_TARGETS, Map.of("name", inside, "kind", SftpStorageKind.ID.toString(),
                    "settings", sftp(server, server.freshFolder("tenant"), server.hostKey())), theirs)))
                .as("step 5: refused").isInstanceOf(Violations.class);
        }
        assertThat(Models.get(BackupTargetModel.class).find().where(BackupTargetModel.NAME.eq(inside)).count())
            .as("step 5: nothing saved").isZero();

        // 5b. Settings naming a host record (an SSH-era row's) are never a customer's: the host's client key is the
        //     operator's alone, so the declaration is refused before anything is opened.
        Row borrowed = Models.get(BackupTargetModel.class).createEmptyRow();
        borrowed.set(BackupTargetModel.NAME, "Borrowed " + tag);
        borrowed.set(BackupTargetModel.KIND, SftpStorageKind.ID.toString());
        borrowed.set(BackupTargetModel.SETTINGS, Map.of("server", "backup-host", "path", "/srv"));
        assertThat(catchThrowable(() -> HohenheimStorages.declarationOf(borrowed,
            HohenheimStorages.ownerOfCreator(theirs)))).as("step 5b: a host record is no customer's")
            .isInstanceOf(IllegalArgumentException.class);

        // 5c. A creator who is nobody (no account, no administrator) declares no operator's storage.
        assertThat(catchThrowable(() -> HohenheimStorages.ownerOfCreator(AccessContext.anonymous())))
            .as("step 5c: nobody owns no operator's storage").isInstanceOf(IllegalStateException.class);

        // 6. Deleted through the twin: it is gone.
        PanelResourceCalls.delete(HohenheimSlugs.MANAGE, HohenheimSlugs.BACKUP_TARGETS, key, theirs);
        assertThat(Models.get(BackupTargetModel.class).findById(key)).as("step 6: deleted").isNull();
    }

    private void chooseKind(String kind) {
        // The kinds are segments of one band, as the board draws them.
        page.locator("pl-toggle-group[name='kind'] pl-toggle-group-item[data-value='" + kind + "']").first().click();
        waitForReactiveIdle();
        waitForReactiveIdle();
    }

    private void fill(String name, String value) {
        page.locator("[name='" + name + "'] input:not([type='hidden']), input[name='" + name
                + "']:not([type='hidden'])")
            .first().fill(value);
    }

    private Row storage(String name, String kind, Map<String, Object> settings) {
        Row row = Models.get(BackupTargetModel.class).createEmptyRow();
        row.set(BackupTargetModel.NAME, name);
        row.set(BackupTargetModel.KIND, kind);
        row.set(BackupTargetModel.SETTINGS, settings);
        Models.get(BackupTargetModel.class).save(row);
        this.made.add(name);
        return row;
    }

    private static Map<String, Object> sftp(SftpTestServer server, String folder, String hostKey) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("host", SftpTestServer.HOST);
        settings.put("folder", folder);
        settings.put("user", SftpTestServer.USER);
        settings.put("sign_in", SftpStorageKind.SIGN_IN_PASSWORD);
        settings.put("password", SftpTestServer.PASSWORD);
        settings.put("host_key", hostKey);
        return settings;
    }

    private void shoot(String name) {
        page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve(name + ".png")).setFullPage(true));
    }
}
