package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.files.InstanceSftpRealm;
import be.elevenways.hohenheim.server.files.HohenheimSftp;
import be.elevenways.hohenheim.server.files.SiteFileMoves;
import be.elevenways.hohenheim.server.files.SiteFiles;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.hohenheim.test.SftpTestClient.Connection;
import be.elevenways.protoblast.common.async.ProgressSink;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.SshKeys;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.storage.LocalDiskStorageAdapter;
import be.elevenways.zenit.server.storage.LocalFolderStorageKind;
import be.elevenways.zenit.server.storage.Storages;
import be.elevenways.zenit.sftp.server.SftpKeyLogins;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static be.elevenways.hohenheim.test.SftpTestClient.names;
import static be.elevenways.hohenheim.test.SftpTestClient.readBytes;
import static be.elevenways.hohenheim.test.SftpTestClient.writeBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Every way a site's own files are reached: its manager signs in over SFTP with his SSH key as {@code <email>.S<code>}
 * and puts installers there that anyone then downloads from the site, a range as a range; the Files tab uploads and
 * downloads in a browser; an assistant reads, writes, moves and deletes them by the site's code. Nobody else reaches
 * them, nor anyone a site serving a folder of the host. Files a change of storage left where they were are reached the
 * same way over each of the three: listed, read and deleted where they stay, new ones written to the storage chosen.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class SiteOwnFilesReachJourneyTest extends HohenheimTestBase {

    private static final String LOOPBACK = "127.0.0.1";
    private static final Pattern SITE_CODE = Pattern.compile("S\\d+");

    private static Path storage;

    @BeforeAll
    static void mountStorage() throws Exception {
        storage = Files.createTempDirectory("hh-site-reach");
        Storages.replace(SiteFiles.STORAGE, new LocalDiskStorageAdapter(storage));
    }

    @AfterAll
    static void unmountStorage() {
        Storages.unregister(SiteFiles.STORAGE);
    }

    /**
     * The path a colleague takes tonight: an operator made the own-files site at an address and granted him manage;
     * he signs in over SFTP with his key, makes a folder, uploads three installers (one past the Files tab's cap),
     * lists and renames; anyone downloads them from the site, whole and by range; each change is in the site's
     * history by him with origin sftp; a key of someone else, his login to a site serving a host folder and an unknown
     * code are all refused.
     */
    @Test
    void aManagersKeyPutsInstallersIntoTheSiteOverSftpAndAnyoneDownloadsThem() throws Exception {
        String tag = HohenheimMcpTest.letters();
        List<Runnable> restore = new ArrayList<>();
        ProxyServer proxy = null;
        try {
            // 0. The operator's own-files site at an address, and a site of his serving a host folder.
            int siteId = site("downloads-" + tag, true);
            String host = "downloads-" + tag + ".files.test";
            ProxyTestSupport.addDomain(Models.get(SiteModel.class).findById(siteId), host, "exact", null, false);
            int folderSiteId = site("folder-" + tag, false);
            String email = "sftp-site-" + tag + "@hohenheim.local";
            UUID manager = ApiSupport.user(email, "Site manager " + tag);
            UUID stranger = ApiSupport.user("sftp-stranger-" + tag + "@hohenheim.local", "Stranger " + tag);
            for (int managed : List.of(siteId, folderSiteId)) {
                RecordGrants.grant(GrantSubjectType.USER, manager, SiteModel.MODEL_ID, managed,
                    HohenheimCapabilities.MANAGE, true);
            }
            assertThat(SftpKeyLogins.DECLARED && SshKeys.offered()).as("step 0: accounts are offered keys").isTrue();
            KeyPair laptop = KeyUtils.generateKeyPair("ssh-ed25519", 256);
            SshKeys.add(manager, "laptop", PublicKeyEntry.toString(laptop.getPublic()));
            KeyPair strangersKey = KeyUtils.generateKeyPair("ssh-ed25519", 256);
            SshKeys.add(stranger, "desk", PublicKeyEntry.toString(strangersKey.getPublic()));
            String code = Objects.requireNonNull(RecordCodes.codeUnjudged(SiteModel.MODEL_ID, String.valueOf(siteId)));
            String folderCode = Objects.requireNonNull(RecordCodes.codeUnjudged(SiteModel.MODEL_ID,
                String.valueOf(folderSiteId)));
            int port = startSftp(restore);
            proxy = ProxyTestSupport.startProxy();
            int proxyPort = ProxyTestSupport.httpPort(proxy);

            byte[] exe = bytes(9 * 1024 * 1024 + 123, 31);
            byte[] dmg = bytes(300 * 1024, 17);
            byte[] deb = bytes(200 * 1024, 7);
            try (SftpTestClient client = SftpTestClient.start()) {
                try (Connection keyed = client.signIn(port, InstanceSftpRealm.username(email, code), laptop)) {
                    // 1. His key signs him in to the site's files, empty at first.
                    assertThat(names(keyed.sftp(), "/")).as("step 1: the site's files, empty").isEmpty();

                    // 2. A folder and three installers, one past the Files tab's cap, stored under the site's keys.
                    keyed.sftp().mkdir("/releases");
                    writeBytes(keyed.sftp(), "/releases/Tadoma Setup 1.2.exe", exe);
                    writeBytes(keyed.sftp(), "/releases/tadoma-1.2.dmg", dmg);
                    writeBytes(keyed.sftp(), "/releases/tadoma_1.2_amd64.deb", deb);
                    Path stored = storage.resolve(SiteFiles.prefixOf(siteId)).resolve("releases");
                    assertThat(stored.resolve("Tadoma Setup 1.2.exe")).as("step 2: stored at its own name")
                        .hasBinaryContent(exe);
                    assertThat(Files.size(stored.resolve("tadoma-1.2.dmg"))).as("step 2: the second")
                        .isEqualTo(dmg.length);

                    // 3. They list, one is renamed, and it reads back byte for byte under its new name.
                    assertThat(names(keyed.sftp(), "/releases")).as("step 3: listed")
                        .containsExactlyInAnyOrder("Tadoma Setup 1.2.exe", "tadoma-1.2.dmg", "tadoma_1.2_amd64.deb");
                    keyed.sftp().rename("/releases/tadoma-1.2.dmg", "/releases/Tadoma 1.2.dmg");
                    assertThat(names(keyed.sftp(), "/releases")).as("step 3: renamed")
                        .contains("Tadoma 1.2.dmg").doesNotContain("tadoma-1.2.dmg");
                    assertThat(readBytes(keyed.sftp(), "/releases/Tadoma 1.2.dmg")).as("step 3: read back")
                        .isEqualTo(dmg);
                }

                // 4. Anyone downloads them from the site: whole, and a range as a range.
                ProxyTestSupport.Counted whole = ProxyTestSupport.rawDownload(proxyPort, host,
                    "/releases/Tadoma%20Setup%201.2.exe");
                assertThat(whole.head()).as("step 4: served whole").startsWith("HTTP/1.1 200")
                    .containsIgnoringCase("accept-ranges: bytes");
                assertThat(whole.bodyBytes()).as("step 4: every byte").isEqualTo(exe.length);
                String range = ProxyTestSupport.rawRequest(proxyPort, host, "/releases/tadoma_1.2_amd64.deb",
                    "Range: bytes=0-9");
                assertThat(range).as("step 4: a range").startsWith("HTTP/1.1 206")
                    .containsIgnoringCase("content-range: bytes 0-9/" + deb.length);
                assertThat(ProxyTestSupport.rawRequest(proxyPort, host, "/releases/tadoma-1.2.dmg"))
                    .as("step 4: the old name is gone").startsWith("HTTP/1.1 404");

                // 5. Every change is in the site's history, by him, from SFTP.
                List<RecordedChanges.Recorded> rows = RecordedChanges.of(Models.get(SiteModel.class), siteId)
                    .stream().filter(recorded -> AccountabilityOrigin.SFTP.token().equals(recorded.origin()))
                    .toList();
                assertThat(rows.stream().flatMap(recorded -> recorded.facts().keySet().stream()).toList())
                    .as("step 5: one fact per change, under the file manager's verbs")
                    .containsExactlyInAnyOrder(
                        HohenheimActivityAction.FILES_MKDIR.id().toString(),
                        HohenheimActivityAction.FILES_UPLOAD.id().toString(),
                        HohenheimActivityAction.FILES_UPLOAD.id().toString(),
                        HohenheimActivityAction.FILES_UPLOAD.id().toString(),
                        HohenheimActivityAction.FILES_RENAME.id().toString());
                assertThat(rows).as("step 5: each by the manager")
                    .allSatisfy(recorded -> assertThat(recorded.actor()).isEqualTo(manager.toString()));

                // 6. Another person's key, his login to a site serving a host folder and an unknown code: refused.
                assertThat(catchThrowable(() -> client.signIn(port, InstanceSftpRealm.username(
                    "sftp-stranger-" + tag + "@hohenheim.local", code), strangersKey)))
                    .as("step 6: who does not manage the site cannot sign in to it").isNotNull();
                assertThat(catchThrowable(() -> client.signIn(port, InstanceSftpRealm.username(email, folderCode),
                    laptop))).as("step 6: a site serving a host folder has no files of its own").isNotNull();
                assertThat(catchThrowable(() -> client.signIn(port, InstanceSftpRealm.username(email, "S987654"),
                    laptop))).as("step 6: an unknown code reads the same").isNotNull();

                // 7. An administrator, who manages every site without a grant on any, signs in with his own key.
                Row operator = TenantConduits.operatorUser();
                KeyPair operatorsKey = KeyUtils.generateKeyPair("ssh-ed25519", 256);
                SshKeys.add(operator.get(UserModel.ID), "operator " + tag,
                    PublicKeyEntry.toString(operatorsKey.getPublic()));
                try (Connection keyed = client.signIn(port,
                        InstanceSftpRealm.username(operator.get(UserModel.EMAIL), code), operatorsKey)) {
                    assertThat(names(keyed.sftp(), "/releases")).as("step 7: the administrator reads the site's files")
                        .contains("Tadoma 1.2.dmg");
                    writeBytes(keyed.sftp(), "/releases/notes.txt", bytes(64, 3));
                }
                assertThat(catchThrowable(() -> client.signIn(port, InstanceSftpRealm.username(
                    operator.get(UserModel.EMAIL), folderCode), operatorsKey)))
                    .as("step 7: a site serving a host folder stays closed to him too").isNotNull();

                // 8. The site's files kept in another storage without moving them: over SFTP he still lists and reads
                //    them, a new file lands in the storage chosen alone, and a delete takes one out of the earlier one.
                Path newer = keptElsewhere(siteId);
                Path earlier = storage.resolve(SiteFiles.prefixOf(siteId)).resolve("releases");
                try (Connection keyed = client.signIn(port, InstanceSftpRealm.username(email, code), laptop)) {
                    assertThat(names(keyed.sftp(), "/releases")).as("step 8: the files not moved are listed")
                        .contains("Tadoma 1.2.dmg", "notes.txt");
                    assertThat(readBytes(keyed.sftp(), "/releases/Tadoma 1.2.dmg")).as("step 8: and read")
                        .isEqualTo(dmg);
                    byte[] next = bytes(4096, 5);
                    writeBytes(keyed.sftp(), "/releases/tadoma-1.3.dmg", next);
                    assertThat(newer.resolve(SiteFiles.prefixOf(siteId)).resolve("releases/tadoma-1.3.dmg"))
                        .as("step 8: a new file lands in the storage chosen").hasBinaryContent(next);
                    assertThat(Files.exists(earlier.resolve("tadoma-1.3.dmg"))).as("step 8: not in the earlier one")
                        .isFalse();
                    assertThat(names(keyed.sftp(), "/releases")).as("step 8: the listing holds both")
                        .contains("tadoma-1.3.dmg", "Tadoma 1.2.dmg");
                    keyed.sftp().remove("/releases/notes.txt");
                    assertThat(Files.exists(earlier.resolve("notes.txt"))).as("step 8: a delete takes it out of the "
                        + "earlier storage").isFalse();
                    assertThat(names(keyed.sftp(), "/releases")).as("step 8: and it is gone")
                        .doesNotContain("notes.txt");
                }
            }
        } finally {
            if (proxy != null) {
                proxy.stop();
            }
            HohenheimSftp.stop();
            restore.forEach(Runnable::run);
            RateLimitMiddleware.limiter().clear();
        }
    }

    /**
     * The site's Files tab as its manager uses it in a browser: it says how to connect over SFTP with his key, uploads
     * a file into the site's own files, lists it and downloads it; a site serving a host folder offers no Files tab, and
     * a person who does not manage the site does not reach it.
     */
    @Test
    void theFilesTabOfASiteUploadsListsAndDownloadsItsOwnFiles() throws Exception {
        String tag = HohenheimMcpTest.letters();
        int siteId = site("tab-" + tag, true);
        int folderSiteId = site("tab-folder-" + tag, false);
        String email = "files-tab-" + tag + "@hohenheim.local";
        UUID manager = ApiSupport.user(email, "Files tab " + tag);
        UUID stranger = ApiSupport.user("files-tab-stranger-" + tag + "@hohenheim.local", "Stranger " + tag);
        for (int managed : List.of(siteId, folderSiteId)) {
            RecordGrants.grant(GrantSubjectType.USER, manager, SiteModel.MODEL_ID, managed,
                HohenheimCapabilities.MANAGE, true);
        }
        SshKeys.add(manager, "laptop", PublicKeyEntry.toString(KeyUtils.generateKeyPair("ssh-ed25519", 256)
            .getPublic()));
        String session = sessionFor(manager).token();
        String code = RecordCodes.codeUnjudged(SiteModel.MODEL_ID, String.valueOf(siteId));
        String tab = filesTab(HohenheimSlugs.MANAGE, siteId);
        List<Runnable> restore = new ArrayList<>();
        try {
            remember(restore, HohenheimSettings.Sftp.ENABLED);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.ENABLED, true);

            // 1. The record offers the tab; it names his login for the site's code and the key that signs him in.
            String files = "/page/" + HohenheimSlugs.Tab.FILES;
            assertThat(httpGet(record(siteId), session).body()).as("step 1: the site offers its Files tab")
                .contains(files);
            assertThat(httpGet(record(folderSiteId), session).body()).as("step 1: a site serving a host folder not")
                .doesNotContain(files);
            navigateToAppAs(tab, session);
            waitForHydration();
            assertThat(page.locator("[data-sftp-username]").textContent().strip())
                .as("step 1: his login for this site").isEqualTo(email + "." + code);
            assertThat(page.locator("[data-sftp-credentials]").textContent()).as("step 1: his key signs in")
                .contains("laptop");

            // 2. A file uploaded through the tab's form lands in the site's own files and is listed.
            String upload = "form[enctype='multipart/form-data']";
            byte[] notes = ("Release notes " + tag).getBytes(StandardCharsets.UTF_8);
            page.locator(upload + " pl-input[name='path'] input").fill("/Release notes.txt");
            page.locator(upload + " input[type='file']").setInputFiles(new FilePayload("Release notes.txt",
                "text/plain", notes));
            page.locator(upload + " pl-button[type='submit']").click();
            page.locator("pl-table-cell code", new Page.LocatorOptions().setHasText("Release notes.txt")).waitFor();
            assertThat(storage.resolve(SiteFiles.prefixOf(siteId)).resolve("Release notes.txt"))
                .as("step 2: stored under the site's keys").hasBinaryContent(notes);

            // 3. It downloads as it was uploaded.
            String download = HohenheimEndpoints.SITE_FILE_DOWNLOAD.with(HohenheimEndpoints.SITE_ID, siteId)
                .with(HohenheimParams.FILES_PATH, "/Release notes.txt").toUrl();
            HttpResponse<byte[]> answer = sendRequestBytes(requestTo(download)
                .header("Cookie", sessionCookieHeader(session)).GET());
            assertThat(answer.statusCode()).as("step 3: the download").isEqualTo(200);
            assertThat(answer.body()).as("step 3: its bytes").isEqualTo(notes);

            // 4. A site serving a host folder offers no Files tab, and another person does not reach this one.
            assertThat(httpGet(filesTab(HohenheimSlugs.MANAGE, folderSiteId), session).statusCode())
                .as("step 4: no Files tab on a site serving a host folder").isEqualTo(404);
            HttpResponse<String> foreign = httpGet(tab, sessionFor(stranger).token());
            assertThat(foreign.statusCode()).as("step 4: not for someone who does not manage it").isIn(403, 404);
            assertThat(foreign.body()).as("step 4: nothing of it shown").doesNotContain("Release notes.txt");

            // 5. Moved into a storage of the operator's and then kept in another without moving them: the tab still
            //    lists the file and it downloads, and the storage it stays in says on its page that the site still
            //    reads from it.
            String movedName = storageRow(Files.createTempDirectory("hh-tab-moved"));
            SiteFileMoves.move(Objects.requireNonNull(Models.get(SiteModel.class).findById(siteId)), movedName,
                ProgressSink.SILENT, step -> { });
            keptElsewhere(siteId);
            navigateToAppAs(tab, session);
            waitForHydration();
            page.locator("pl-table-cell code", new Page.LocatorOptions().setHasText("Release notes.txt")).waitFor();
            HttpResponse<byte[]> again = sendRequestBytes(requestTo(download)
                .header("Cookie", sessionCookieHeader(session)).GET());
            assertThat(again.body()).as("step 5: it downloads from the storage it stays in").isEqualTo(notes);
            Object movedId = Objects.requireNonNull(Models.get(BackupTargetModel.class).find()
                .where(BackupTargetModel.NAME.eq(movedName)).first()).get(BackupTargetModel.ID);
            String operator = sessionFor(TenantConduits.operatorUser().get(UserModel.ID)).token();
            navigateToAppAs(CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.BACKUP_TARGETS, movedId,
                RecordOverview.SLUG).toUrl(), operator);
            waitForHydration();
            assertThat(page.content()).as("step 5: its page says the site still reads from it")
                .contains("Files not moved yet: the site still reads them here");
        } finally {
            restore.forEach(Runnable::run);
        }
    }

    /**
     * The four file tools on a site's code, through the real MCP endpoint: the operator makes the own-files site by
     * tool; its manager's assistant makes a folder, writes, reads a listing and a file, moves and deletes; a wrong
     * argument is refused by its name, a site serving a host folder and someone else's assistant are refused.
     */
    @Test
    void anAssistantReadsWritesMovesAndDeletesASitesOwnFilesByItsCode() throws IOException {
        String tag = HohenheimMcpTest.letters();
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        UUID adminId = TenantConduits.operatorUser().get(UserModel.ID);
        McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint, adminId, "Test Admin");

        // 1. The operator makes the site serving its own files, by tool.
        McpHttpSession.Answer made = admin.call("site_static_files_set", Map.of("name", "Tool files " + tag,
            "own_files", true));
        assertThat(made.error()).as("step 1: " + made.text()).isFalse();
        String code = code(made.text(), "step 1");
        int siteId = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(SiteModel.MODEL_ID, code)));
        assertThat(SiteFiles.servesOwnFiles(Models.get(SiteModel.class).findById(siteId)))
            .as("step 1: it serves its own files").isTrue();
        McpHttpSession.Answer folderSite = admin.call("site_static_files_set", Map.of("name", "Tool folder " + tag));
        String folderCode = code(folderSite.text(), "step 1: a site serving a host folder");
        int folderSiteId = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(SiteModel.MODEL_ID,
            folderCode)));
        UUID manager = ApiSupport.user("files-tool-" + tag + "@hohenheim.local", "Files tool " + tag);
        RecordGrants.grant(GrantSubjectType.USER, manager, SiteModel.MODEL_ID, siteId, HohenheimCapabilities.MANAGE,
            true);
        McpHttpSession own = HohenheimMcpTest.presetSession(server, endpoint, manager, "Files tool " + tag);

        // 2. His assistant makes a folder and writes a file into it.
        McpHttpSession.Answer folder = own.call("file_write", Map.of("code", code, "path", "/docs", "folder", true));
        assertThat(folder.error()).as("step 2: " + folder.text()).isFalse();
        McpHttpSession.Answer written = own.call("file_write", Map.of("code", code, "path", "/docs/Read me.txt",
            "content", "hello " + tag));
        assertThat(written.error()).as("step 2: " + written.text()).isFalse();
        assertThat(storage.resolve(SiteFiles.prefixOf(siteId)).resolve("docs/Read me.txt"))
            .as("step 2: stored under the site's keys").hasContent("hello " + tag);

        // 3. A folder reads as its entries, a file as its text.
        McpHttpSession.Answer listing = own.call("file_read", Map.of("code", code, "path", "/docs"));
        assertThat(listing.error()).as("step 3: " + listing.text()).isFalse();
        assertThat(listing.text()).as("step 3: the folder's entries").contains("entries: 1").contains("Read me.txt");
        assertThat(own.call("file_read", Map.of("code", code, "path", "/docs/Read me.txt")).text())
            .as("step 3: the file's text").contains("hello " + tag);

        // 4. A move renames it; a delete removes it.
        McpHttpSession.Answer moved = own.call("file_move", Map.of("code", code, "path", "/docs/Read me.txt",
            "to", "/docs/README.txt"));
        assertThat(moved.error()).as("step 4: " + moved.text()).isFalse();
        assertThat(own.call("file_read", Map.of("code", code, "path", "/docs")).text()).as("step 4: moved")
            .contains("README.txt").doesNotContain("Read me.txt");
        McpHttpSession.Answer deleted = own.call("file_delete", Map.of("code", code, "path", "/docs/README.txt"));
        assertThat(deleted.error()).as("step 4: " + deleted.text()).isFalse();
        assertThat(Files.exists(storage.resolve(SiteFiles.prefixOf(siteId)).resolve("docs/README.txt")))
            .as("step 4: deleted").isFalse();

        // 5. A wrong argument is refused by its name; a site serving a host folder and another's assistant too.
        McpHttpSession.Answer wrong = own.call("file_write", Map.of("code", code, "path", "/x.txt", "text", "x"));
        assertThat(wrong.error()).as("step 5: " + wrong.text()).isTrue();
        assertThat(wrong.text()).as("step 5: the refusal names it").contains("text");
        assertThat(admin.call("file_read", Map.of("code", folderCode)).error())
            .as("step 5: a site serving a host folder has no files of its own").isTrue();
        UUID stranger = ApiSupport.user("files-tool-stranger-" + tag + "@hohenheim.local", "Stranger " + tag);
        RecordGrants.grant(GrantSubjectType.USER, stranger, SiteModel.MODEL_ID, folderSiteId,
            HohenheimCapabilities.MANAGE, true);
        McpHttpSession foreign = HohenheimMcpTest.presetSession(server, endpoint, stranger, "Stranger " + tag);
        McpHttpSession.Answer refused = foreign.call("file_read", Map.of("code", code, "path", "/docs"));
        assertThat(refused.error()).as("step 5: " + refused.text()).isTrue();
        assertThat(refused.text()).as("step 5: nothing of it").doesNotContain("Read me.txt");

        // 6. Kept in another storage without moving them: the file left behind is listed and read by tool, a new one
        //    lands in the storage chosen, and a delete takes the old one out of the earlier storage.
        McpHttpSession.Answer kept = own.call("file_write", Map.of("code", code, "path", "/docs/Kept.txt",
            "content", "kept " + tag));
        assertThat(kept.error()).as("step 6: " + kept.text()).isFalse();
        Path newer = keptElsewhere(siteId);
        assertThat(own.call("file_read", Map.of("code", code, "path", "/docs/Kept.txt")).text())
            .as("step 6: the file left behind reads").contains("kept " + tag);
        assertThat(own.call("file_write", Map.of("code", code, "path", "/docs/New.txt", "content", "new " + tag))
            .error()).as("step 6: a new one is written").isFalse();
        assertThat(newer.resolve(SiteFiles.prefixOf(siteId)).resolve("docs/New.txt"))
            .as("step 6: in the storage chosen").hasContent("new " + tag);
        assertThat(own.call("file_read", Map.of("code", code, "path", "/docs")).text())
            .as("step 6: the listing holds both").contains("Kept.txt").contains("New.txt");
        assertThat(own.call("file_delete", Map.of("code", code, "path", "/docs/Kept.txt")).error())
            .as("step 6: the old one is deleted").isFalse();
        assertThat(Files.exists(storage.resolve(SiteFiles.prefixOf(siteId)).resolve("docs/Kept.txt")))
            .as("step 6: out of the earlier storage").isFalse();
    }

    // -- plumbing -------------------------------------------------------------

    /** @return a new static site, serving its own files or a (blank) host folder */
    private static int site(String slug, boolean ownFiles) {
        Row site = ProxyTestSupport.setupSite("hohenheim:static", slug, slug,
            new LinkedHashMap<>(Map.of("own_files", ownFiles, "autoindex", true)));
        return site.get(SiteModel.ID);
    }

    /**
     * Changes where the site keeps its files to a new storage of the operator's, by a save alone: the files stay
     * where they are.
     *
     * @return the new storage's folder
     */
    private static Path keptElsewhere(int siteId) throws IOException {
        Path folder = Files.createTempDirectory("hh-site-unmoved");
        String name = storageRow(folder);
        Row site = Objects.requireNonNull(Models.get(SiteModel.class).findById(siteId));
        Map<String, Object> settings = new LinkedHashMap<>(RawValues.map(site.get(SiteModel.SETTINGS)));
        settings.put(StaticUpstreamKind.STORAGE.getName(), name);
        site.set(SiteModel.SETTINGS, settings);
        Models.get(SiteModel.class).save(site);
        return folder;
    }

    /** @return the name of a new storage of the operator's on a folder of this server */
    private static String storageRow(Path folder) {
        String name = "hh-reach-" + UUID.randomUUID();
        Row row = Models.get(BackupTargetModel.class).createEmptyRow();
        row.set(BackupTargetModel.NAME, name);
        row.set(BackupTargetModel.KIND, LocalFolderStorageKind.ID.toString());
        row.set(BackupTargetModel.SETTINGS, Map.of(LocalFolderStorageKind.FOLDER.getName(), folder.toString()));
        Models.get(BackupTargetModel.class).save(row);
        return name;
    }

    /** @return the port of the SFTP server started from hohenheim.sftp.*, the settings put back by restore */
    private static int startSftp(List<Runnable> restore) throws Exception {
        remember(restore, HohenheimSettings.Sftp.ENABLED);
        remember(restore, HohenheimSettings.Sftp.PORT);
        remember(restore, HohenheimSettings.Sftp.BIND_ADDRESS);
        remember(restore, HohenheimSettings.Storage.DATA_PATH);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.ENABLED, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.PORT, 0);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.BIND_ADDRESS, LOOPBACK);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
            Files.createTempDirectory("hh-site-sftp").toString());
        HohenheimSftp.startIfEnabled();
        assertThat(HohenheimSftp.failure()).as("the SFTP server started").isNull();
        return Objects.requireNonNull(HohenheimSftp.server()).port();
    }

    private static <T> void remember(List<Runnable> restore, SettingDefinition<T> setting) {
        T before = Zenit.SETTINGS_VALUES.getValue(setting);
        restore.add(() -> Zenit.SETTINGS_VALUES.setValue(setting, before));
    }

    private static String filesTab(String panel, int siteId) {
        return CmsRoutes.subpage(panel, HohenheimSlugs.SITES, siteId, HohenheimSlugs.Tab.FILES).toUrl();
    }

    private static String record(int siteId) {
        return CmsRoutes.detail(HohenheimSlugs.MANAGE, HohenheimSlugs.SITES, siteId).toUrl();
    }

    private static String code(String answer, String step) {
        Matcher found = SITE_CODE.matcher(answer);
        assertThat(found.find()).as(step + ": the answer names the site's code: " + answer).isTrue();
        return found.group();
    }

    /** @return bytes of a pattern of their own, so two files never read alike */
    private static byte[] bytes(int size, int step) {
        byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i * step + 7);
        }
        return content;
    }
}
