package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSlugs;
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
            assertThat(list).as("step 1: the settings' storages, read-only")
                .contains("From the server's settings").contains("hohenheim-site-files");
            for (String head : List.of("Kind", "Where", "Added by", "Used by", "Room left")) {
                assertThat(page.locator("pl-table-head").allTextContents().toString()).as("step 1: " + head)
                    .contains(head);
            }
            shoot("storages-list");

            // 2. A storage's page (its overview tab; the record's own address is its edit form): where it is in its
            //    kind's facts, its secret set and never shown, its room and what uses it.
            navigateToApp("/admin/" + HohenheimSlugs.BACKUP_TARGETS + "/" + box.get(BackupTargetModel.ID) + "/page/"
                + RecordOverview.SLUG);
            waitForHydration();
            String shown = page.content();
            assertThat(shown).as("step 2: where").contains("Host", SftpTestServer.HOST, "Server's key");
            assertThat(shown).as("step 2: the secret is set, never shown").contains("Sign-in secrets", "Password")
                .doesNotContain(SftpTestServer.PASSWORD);
            shoot("storage-page");

            // 3. Adding a storage: the kind's own form, the S3 one with its bucket and the path-style switch.
            navigateToApp("/admin/" + HohenheimSlugs.BACKUP_TARGETS + "/new");
            waitForHydration();
            chooseKind(S3StorageKind.ID.toString());
            page.waitForSelector("pl-input[name='settings.bucket']");
            assertThat(page.content()).as("step 3: the S3 form").contains("Bucket", "Folder inside the bucket",
                "Secret key", "Bucket name in the path");
            shoot("storage-add-s3");

            // 4. An SFTP server whose key is not confirmed yet: saving asks whether it is the right server and shows
            //    the key it presented; nothing is saved.
            chooseKind(SftpStorageKind.ID.toString());
            page.waitForSelector("pl-input[name='settings.host']");
            String unpinned = "Unpinned " + tag;
            fill("name", unpinned);
            fill("settings.host", SftpTestServer.HOST);
            fill("settings.folder", server.freshFolder("screens"));
            fill("settings.user", SftpTestServer.USER);
            fill("settings.password", SftpTestServer.PASSWORD);
            page.locator(".cms-form-actions pl-button[type='submit']").first().click();
            page.waitForSelector("text=Is this the right server?");
            assertThat(page.content()).as("step 4: the key it presented").contains(server.hostKey());
            assertThat(Models.get(BackupTargetModel.class).find().where(BackupTargetModel.NAME.eq(unpinned)).count())
                .as("step 4: nothing saved").isZero();
            shoot("storage-add-sftp-key");
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

        // 6. Deleted through the twin: it is gone.
        PanelResourceCalls.delete(HohenheimSlugs.MANAGE, HohenheimSlugs.BACKUP_TARGETS, key, theirs);
        assertThat(Models.get(BackupTargetModel.class).findById(key)).as("step 6: deleted").isNull();
    }

    private void chooseKind(String kind) {
        String card = "pl-choice-group[name='kind'] pl-choice-card[data-value='" + kind + "'] button";
        if (page.locator(card).count() > 0) {
            page.click(card);
        } else {
            chooseOption("kind", kind);
        }
        waitForReactiveIdle();
        waitForReactiveIdle();
    }

    private void fill(String name, String value) {
        page.locator("[name='" + name + "'] input:not([type='hidden']), input[name='" + name + "']:not([type='hidden'])")
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
