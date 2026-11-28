package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.backup.BackupStorages;
import be.elevenways.hohenheim.server.backup.HohenheimStorages;
import be.elevenways.hohenheim.server.cms.BackupTargetParts;
import be.elevenways.hohenheim.server.files.SitePicks;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.test.stack.StackServiceSecretsTest;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.storage.LocalFolderStorageKind;
import be.elevenways.zenit.server.storage.StorageKinds;
import be.elevenways.zenit.server.storage.StorageOwner;
import be.elevenways.zenit.server.storage.StorageReach;
import be.elevenways.zenit.server.storage.StorageReadings;
import be.elevenways.zenit.storage.s3.server.S3StorageKind;
import be.elevenways.zenit.storage.s3.test.support.GarageServer;
import be.elevenways.zenit.storage.s3.test.support.S3TestServer;
import be.elevenways.zenit.storage.sftp.server.SftpStorageKind;
import be.elevenways.zenit.storage.sftp.test.support.SftpTestServer;
import be.elevenways.zenit.test.support.OutboundFixture;
import be.elevenways.zenit.test.support.PrivateNetworkBootFixture;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

            // 1. The one Storages list: name, kind, where (the place, then the rest in muted words under it), added
            //    by, used by and room left; the storages of the server's settings are rows of it, read-only, saying
            //    they come from the server's settings, with no Edit; each record has its Test and its Edit.
            navigateToApp("/admin/" + HohenheimSlugs.STORAGES);
            waitForHydration();
            String list = page.content();
            assertThat(list).as("step 1: the records").contains("Backup disk " + tag, "Storage box " + tag)
                .contains("Folder on this server", "SFTP server").contains(folder.toString())
                .contains(SftpTestServer.HOST).contains("folder " + boxFolder);
            Locator siteFiles = page.locator("pl-table-row").filter(new Locator.FilterOptions().setHasText("Site files"));
            assertThat(siteFiles.count()).as("step 1: this server's site files are one row of the list").isEqualTo(1);
            assertThat(siteFiles.textContent()).as("step 1: a settings row says where it comes from")
                .contains("From the server's settings", "Folder on this server").doesNotContain("Edit");
            assertThat(list).as("step 1: no separate card, and never a module's own home")
                .doesNotContain("Declared in this server's settings file").doesNotContain("/data/media")
                .doesNotContain("/data/content");
            Locator diskRow = page.locator("pl-table-row").filter(new Locator.FilterOptions()
                .setHasText("Backup disk " + tag));
            assertThat(diskRow.textContent()).as("step 1: a record offers Test and Edit").contains("Test", "Edit");
            for (String head : List.of("Kind", "Where", "Added by", "Used by", "Room left")) {
                assertThat(page.locator("pl-table-head").allTextContents().toString()).as("step 1: " + head)
                    .contains(head);
            }
            shoot("storages-list");
            diskRow.locator("a, button").filter(new Locator.FilterOptions().setHasText("Edit")).first().click();
            page.waitForURL("**/" + HohenheimSlugs.BACKUP_TARGETS + "/" + disk.get(BackupTargetModel.ID) + "**");

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

        // 5. A customer's list: their own storages, "You" as who added them, never the operator's own, and the ones
        //    an administrator lets everyone use by name and kind alone, "Shared with everyone", no address, no Test.
        UUID customer = ApiSupport.user("storage-screens-" + tag + "@hohenheim.local", "Mira Voss");
        Path own = Files.createTempDirectory("hh-own-storage");
        Row mine = storage("Tadoma backups " + tag, LocalFolderStorageKind.ID.toString(),
            Map.of(LocalFolderStorageKind.FOLDER.getName(), own.toString()));
        RecordGrants.grant(GrantSubjectType.USER, customer, BackupTargetModel.MODEL_ID, mine.get(BackupTargetModel.ID),
            HohenheimCapabilities.MANAGE, true);
        Path sharedFolder = Files.createTempDirectory("hh-shared-storage");
        Row shared = storage("Shared bucket " + tag, LocalFolderStorageKind.ID.toString(),
            Map.of(LocalFolderStorageKind.FOLDER.getName(), sharedFolder.toString()));
        shared.set(BackupTargetModel.SHARED, true);
        Models.get(BackupTargetModel.class).save(shared);
        navigateToAppAs("/manage/" + HohenheimSlugs.STORAGES, sessionFor(customer).token());
        waitForHydration();
        String theirs = page.content();
        assertThat(theirs).as("step 5: their own").contains("Tadoma backups " + tag, "You")
            .doesNotContain("Backup disk " + tag).doesNotContain("From the server's settings");
        Locator sharedRow = page.locator("pl-table-row").filter(new Locator.FilterOptions()
            .setHasText("Shared bucket " + tag));
        assertThat(sharedRow.count()).as("step 5: the storage everyone may use is a row").isEqualTo(1);
        assertThat(sharedRow.textContent()).as("step 5: by name and kind alone")
            .contains("Folder on this server", "Shared with everyone").doesNotContain(sharedFolder.toString())
            .doesNotContain("Test").doesNotContain("Edit");
        AccessContext customerAccess = AccessContext.of(TenantConduits.stubIn(new UserPrincipal(customer,
            "user-" + customer), HohenheimSlugs.MANAGE));
        assertThat(SitePicks.offered(customerAccess)).extracting(FieldOption::value)
            .as("step 5: and they may choose it where a storage is chosen")
            .contains("Shared bucket " + tag, "Tadoma backups " + tag).doesNotContain("Backup disk " + tag);
        assertThat(PanelResourceCalls.tab(HohenheimSlugs.MANAGE, HohenheimSlugs.BACKUP_TARGETS,
            shared.get(BackupTargetModel.ID), RecordOverview.SLUG, customerAccess).status())
            .as("step 5: never theirs to open").isEqualTo(404);
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
     * and deleted; one whose grant is taken away reaches the public internet alone; an operator's storage is not theirs
     * to open, and one aimed at a private address is refused.
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
            assertThat(BackupTargetModel.addedByOf(saved)).as("step 1: and is named as who added it")
                .isEqualTo(theirs.principal().reference());

            // 2. Its Test runs the storage's own check against the server; what it found is what the list says next.
            assertThat(PanelResourceCalls.invoke(HohenheimSlugs.MANAGE, HohenheimSlugs.BACKUP_TARGETS,
                BackupTargetParts.TEST.id(), key, null, theirs)).as("step 2: the Test answered").isNotNull();
            assertThat(StorageReadings.of(name).check()).as("step 2: checked").isNotNull()
                .satisfies(check -> assertThat(check.isUsable()).as(check.diagnostic()).isTrue());

            // 3. Renamed through the twin; a customer's storage is stored with the operator's two switches off on
            //    every write, a direct one carrying them on included.
            saved.set(BackupTargetModel.SHARED, true);
            saved.set(BackupTargetModel.PRIVATE_ADDRESSES_ALLOWED, true);
            Models.get(BackupTargetModel.class).saveWritten(saved);
            Row direct = Objects.requireNonNull(Models.get(BackupTargetModel.class).findById(key));
            assertThat(List.of(direct.get(BackupTargetModel.SHARED),
                direct.get(BackupTargetModel.PRIVATE_ADDRESSES_ALLOWED))).as("step 3: a direct write keeps them off")
                .containsExactly(false, false);
            PanelResourceCalls.patch(HohenheimSlugs.MANAGE, HohenheimSlugs.BACKUP_TARGETS, key,
                Map.of("name", "Lumen archive " + tag), theirs);
            Row renamed = Objects.requireNonNull(Models.get(BackupTargetModel.class).findById(key));
            assertThat((String) renamed.get(BackupTargetModel.NAME)).as("step 3: renamed")
                .isEqualTo("Lumen archive " + tag);
            assertThat(List.of(renamed.get(BackupTargetModel.SHARED),
                renamed.get(BackupTargetModel.PRIVATE_ADDRESSES_ALLOWED))).as("step 3: neither switch is theirs")
                .containsExactly(false, false);

            // 3b. Its customer's grant removed, the storage is the operator's with the switch off: it reaches the
            //     public internet alone, never a private address. The grant given back, it is theirs again.
            RecordGrants.revokeAllForRecord(BackupTargetModel.MODEL_ID, key);
            Row orphaned = Objects.requireNonNull(Models.get(BackupTargetModel.class).findById(key));
            assertThat(HohenheimStorages.ownerOf(orphaned)).as("step 3b: owned by the operator now")
                .isInstanceOf(StorageOwner.Operator.class);
            assertThat(HohenheimStorages.declarationOf(orphaned).reach()).as("step 3b: public reach alone")
                .isEqualTo(StorageReach.PUBLIC_INTERNET);
            RecordGrants.grant(GrantSubjectType.USER, customer, BackupTargetModel.MODEL_ID, key,
                HohenheimCapabilities.MANAGE, true);
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

            // 5a. An operator's storage at that private address: refused while its own switch is off, though the
            //     boot allows private networks; with the switch on it passes the save's check and connects.
            Row lanBox = Models.get(BackupTargetModel.class).createEmptyRow();
            lanBox.set(BackupTargetModel.NAME, "LAN box " + tag);
            lanBox.set(BackupTargetModel.KIND, SftpStorageKind.ID.toString());
            lanBox.set(BackupTargetModel.SETTINGS, sftp(server, server.freshFolder("operator"), server.hostKey()));
            lanBox.set(BackupTargetModel.PRIVATE_ADDRESSES_ALLOWED, false);
            assertThat(catchThrowable(() -> BackupTargetParts.judgeBeforeSave(lanBox, StorageOwner.OPERATOR)))
                .as("step 5a: the switch off refuses a private address").isInstanceOf(Violations.class);
            lanBox.set(BackupTargetModel.PRIVATE_ADDRESSES_ALLOWED, true);
            assertThat(catchThrowable(() -> BackupTargetParts.judgeBeforeSave(lanBox, StorageOwner.OPERATOR)))
                .as("step 5a: the switch on with the boot's opt-in connects").isNull();
        }

        // 5a'. Without the boot's opt-in, the switch alone reaches no private address.
        try (OutboundFixture lan = OutboundFixture.routeResolved(SftpTestServer.HOST,
                InetAddress.getByName("10.0.0.5"), server.port())) {
            Row lanBox = Models.get(BackupTargetModel.class).createEmptyRow();
            lanBox.set(BackupTargetModel.NAME, "LAN box " + tag);
            lanBox.set(BackupTargetModel.KIND, SftpStorageKind.ID.toString());
            lanBox.set(BackupTargetModel.SETTINGS, sftp(server, server.freshFolder("operator"), server.hostKey()));
            lanBox.set(BackupTargetModel.PRIVATE_ADDRESSES_ALLOWED, true);
            assertThat(catchThrowable(() -> BackupTargetParts.judgeBeforeSave(lanBox, StorageOwner.OPERATOR)))
                .as("step 5a': the switch needs the boot's opt-in too").isInstanceOf(Violations.class);
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

    /**
     * Storages stored before storages were records, in their former settings, read with their real values by the read
     * tool, the form and the page (the Starfleet rehearsal read them as empty): a folder target's next save writes the
     * folder kind's shape and keeps working; an SSH target follows its host record (blocked for workloads, as
     * Starfleet's is, its pin on a key the server presents only when asked for it), shows where it goes and never the
     * host's client key, passes its test, keeps its link through a save that changes nothing it decides and refuses
     * one that changes where it goes.
     */
    @Test
    void storagesStoredBeforeTheRoundReadWithTheirRealValues() throws Exception {
        String tag = HohenheimMcpTest.letters();
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer mcp = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        McpHttpSession admin = HohenheimMcpTest.presetSession(mcp, endpoint,
            TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
        SftpTestServer server = SftpTestServer.shared();
        Integer hostId = null;
        try (OutboundFixture ignored = server.route()) {
            // 1. A folder target's row as Starfleet holds it (its kind column rewritten by the boot's reconciler, its
            //    settings {"path": ...} as stored): the tool, the form and the page read its folder.
            Path folder = Files.createTempDirectory("hh-former-folder");
            Row disk = storage("Former disk " + tag, LocalFolderStorageKind.ID.toString(),
                Map.of("path", folder.toString()));
            int diskId = disk.get(BackupTargetModel.ID);
            assertThat(String.join("", StackServiceSecretsTest.rawColumn(
                "SELECT settings FROM backup_targets WHERE id = ?", diskId))).as("step 1: stored in the former shape")
                .contains("\"path\"").doesNotContain("\"folder\"");
            String diskCode = HohenheimMcpTest.code(BackupTargetModel.MODEL_ID, diskId, TenantConduits.operator());
            assertThat(admin.call("read", Map.of("codes", List.of(diskCode))).text()).as("step 1: the read tool")
                .contains(folder.toString()).doesNotContain("Folder: not set");
            Row found = Objects.requireNonNull(Models.get(BackupTargetModel.class).findById(diskId), "step 1");
            assertThat(RawValues.map(BackupTargetParts.admin().formValues(found).get(BackupTargetModel.SETTINGS
                .getName()))).as("step 1: the form").containsEntry("folder", folder.toString());
            navigateToApp("/admin/" + HohenheimSlugs.BACKUP_TARGETS + "/" + diskId + "/page/" + RecordOverview.SLUG);
            waitForHydration();
            assertThat(page.content()).as("step 1: the page").contains(folder.toString());

            // 2. Its next save (the form's, through the tool) writes the folder kind's shape, and it keeps working.
            McpHttpSession.Answer resaved = admin.call("backup_target_set", Map.of("code", diskCode, "settings",
                Map.of("folder", folder.toString())));
            assertThat(resaved.error()).as("step 2: saved: " + resaved.text()).isFalse();
            assertThat(String.join("", StackServiceSecretsTest.rawColumn(
                "SELECT settings FROM backup_targets WHERE id = ?", diskId))).as("step 2: the folder kind's shape")
                .contains("\"folder\"").doesNotContain("\"path\"");
            BackupStorages.requireUsable(BackupStorages.of(Objects.requireNonNull(
                Models.get(BackupTargetModel.class).findById(diskId), "step 2")));

            // 3. An SSH target's row ({"server": ..., "path": ...}, its kind column rewritten) on a host record blocked
            //    for workloads, its pin confirmed on the server's Ed25519 key, which the server presents only when
            //    asked for it: the tool, the form and the page read where it goes and the server it follows, never the
            //    host's client key.
            String boxFolder = server.freshFolder("former-box");
            hostId = sshHost("phoenix-" + tag, server);
            Row box = storage("Former box " + tag, SftpStorageKind.ID.toString(),
                Map.of("server", HostModel.registryKeyOf(hostId), "path", boxFolder + "/"));
            int boxId = box.get(BackupTargetModel.ID);
            String pin = "ssh-ed25519 " + server.hostKey("ssh-ed25519");
            String clientKey = Objects.requireNonNull(Objects.requireNonNull(Models.get(HostModel.class)
                .findById(hostId)).get(HostTrustSlot.SSH.clientPrivate()), "step 3: the host's client key");
            String boxCode = HohenheimMcpTest.code(BackupTargetModel.MODEL_ID, boxId, TenantConduits.operator());
            String read = admin.call("read", Map.of("codes", List.of(boxCode))).text();
            assertThat(read).as("step 3: the read tool").contains(SftpTestServer.HOST, boxFolder, SftpTestServer.USER,
                server.hostKey("ssh-ed25519")).doesNotContain("Host: not set").doesNotContain(clientKey);
            Map<String, Object> form = RawValues.map(BackupTargetParts.admin().formValues(Objects.requireNonNull(
                Models.get(BackupTargetModel.class).findById(boxId), "step 3")).get(BackupTargetModel.SETTINGS
                .getName()));
            assertThat(form).as("step 3: the form").containsEntry("host", SftpTestServer.HOST)
                .containsEntry("folder", boxFolder).containsEntry("user", SftpTestServer.USER)
                .containsEntry("host_key", pin).containsEntry("sign_in", SftpStorageKind.SIGN_IN_KEY)
                .doesNotContainKey("private_key");
            navigateToApp("/admin/" + HohenheimSlugs.BACKUP_TARGETS + "/" + boxId + "/page/" + RecordOverview.SLUG);
            waitForHydration();
            assertThat(page.content()).as("step 3: the page").contains(SftpTestServer.HOST, boxFolder,
                "Follows server", "phoenix-" + tag).doesNotContain(clientKey);

            // 4. Its test passes: the pinned Ed25519 key is asked for, though the server presents ECDSA first.
            McpHttpSession.Answer tested = admin.call("test", Map.of("code", boxCode));
            assertThat(tested.text()).as("step 4: reachable").contains("test: passed");

            // 5. A rename keeps the link; the form's settings with only its server changed (where it goes is the host
            //    record's) are refused naming the server it follows, and the link is kept.
            McpHttpSession.Answer renamed = admin.call("backup_target_set", Map.of("code", boxCode, "name",
                "Renamed box " + tag));
            assertThat(renamed.error()).as("step 5: renamed: " + renamed.text()).isFalse();
            this.made.add("Renamed box " + tag);
            Map<String, Object> elsewhere = new LinkedHashMap<>(form);
            elsewhere.put("host", "elsewhere.example.test");
            McpHttpSession.Answer moved = admin.call("backup_target_set", Map.of("code", boxCode, "settings",
                elsewhere));
            assertThat(moved.error()).as("step 5: refused: " + moved.text()).isTrue();
            assertThat(moved.text()).as("step 5: naming the server it follows").contains("follows the server",
                "phoenix-" + tag);
            assertThat(String.join("", StackServiceSecretsTest.rawColumn(
                "SELECT settings FROM backup_targets WHERE id = ?", boxId))).as("step 5: still the link")
                .contains("\"server\"").doesNotContain("elsewhere");
        } finally {
            for (String name : List.copyOf(this.made)) {
                Models.get(BackupTargetModel.class).find().where(BackupTargetModel.NAME.eq(name)).delete();
            }
            if (hostId != null) {
                Models.get(HostModel.class).find().where(HostModel.ID.eq(hostId)).delete();
            }
        }
    }

    /**
     * @return a host record reaching the test SFTP server over ssh, left blocked for workloads, its client key the
     *         server's login key and its pin confirmed on the server's Ed25519 host key
     */
    private static int sshHost(String name, SftpTestServer server) throws Exception {
        HostModel hosts = Models.get(HostModel.class);
        Row host = hosts.createEmptyRow();
        host.set(HostModel.NAME, name);
        host.set(HostModel.RUNTIME, HostModel.RUNTIME_DOCKER);
        host.set(HostModel.MODE, HostModel.MODE_SSH);
        host.set(HostModel.SSH_TARGET, SftpTestServer.USER + "@" + SftpTestServer.HOST);
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(server.loginKey(), "test",
            (OpenSSHKeyEncryptionContext) null, key);
        host.set(HostModel.IDENTITY_PRIVATE_KEY, key.toString(StandardCharsets.UTF_8));
        hosts.save(host);
        int id = host.get(HostModel.ID);
        HostPins.apply(Objects.requireNonNull(hosts.findById(id)), HostTrustSlot.SSH,
            server.hostKeyLine("ssh-ed25519"), server.hostKey("ssh-ed25519"));
        HostPins.confirm(Objects.requireNonNull(hosts.findById(id)), HostTrustSlot.SSH);
        assertThat((String) Objects.requireNonNull(hosts.findById(id)).get(HostModel.ADMISSION))
            .as("the host is blocked for workloads").isEqualTo(HostModel.ADMISSION_BLOCKED);
        return id;
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
