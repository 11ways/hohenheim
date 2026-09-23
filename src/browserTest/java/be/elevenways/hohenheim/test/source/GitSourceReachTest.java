package be.elevenways.hohenheim.test.source;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.source.GitCheckout;
import be.elevenways.hohenheim.server.source.GitProviderClient;
import be.elevenways.hohenheim.server.source.GitProviders;
import be.elevenways.hohenheim.server.source.GitRepository;
import be.elevenways.hohenheim.server.source.GiteaProviderKind;
import be.elevenways.hohenheim.source.GitRefNames;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * How far a source or provider record may reach: a tenant's repository URL, branch and
 * provider base URL are somebody else's text, so none of them may name the controller's
 * filesystem, pass an option to git, or aim a request at a private address.
 */
class GitSourceReachTest extends HohenheimTestBase {

    /**
     * The clone-URL and ref grammars, judged without running git.
     */
    @Test
    void theGrammarsRefuseOptionsHelpersAndLocalPathsForARemoteOnlySource() {

        // 1. Every remote spelling a forge hands out stays a remote clone URL.
        for (String remote : List.of("https://git.example.test/team/app.git",
                "ssh://git@git.example.test/team/app.git", "git@git.example.test:team/app.git",
                "git://git.example.test/app.git")) {
            assertThat(GitRepository.isRemoteCloneUrl(remote))
                .as("step 1: " + remote + " is a remote clone URL").isTrue();
        }

        // 2. A controller path is a LOCAL source: still git grammar, never a remote one.
        for (String local : List.of("/srv/repos/app.git", "file:///srv/repos/app.git")) {
            assertThat(GitRepository.isRemoteCloneUrl(local))
                .as("step 2: " + local + " is not remote").isFalse();
            assertThat(GitRepository.isLocalCloneUrl(local))
                .as("step 2: " + local + " is local").isTrue();
            assertThat(GitRepository.isSupportedCloneUrl(local))
                .as("step 2: and the grammar itself still knows it").isTrue();
        }

        // 3. Nothing git could read as an option or a remote helper is a clone URL at all.
        for (String hostile : List.of("-uhost:repo", "--upload-pack=touch x",
                "ssh://-oProxyCommand=touch/repo", "ssh://-oProxyCommand=x@git.example.test/repo",
                "-oProxyCommand=x@git.example.test:repo",
                "ext::sh", "a.b::x")) {
            assertThat(GitRepository.isSupportedCloneUrl(hostile))
                .as("step 3: " + hostile + " is refused").isFalse();
        }

        // 4. Refs follow git's check-ref-format, plus no leading '-' or '+'.
        for (String ref : List.of("main", "feature/login", "release-1.0", "v2.3.4",
                "0123456789abcdef0123456789abcdef01234567")) {
            assertThat(GitRefNames.isValid(ref)).as("step 4: " + ref + " is a ref").isTrue();
        }
        for (String ref : List.of("", "-x", "--upload-pack=x", "+main", "a..b", "a b", "a~1",
                "a^", "a:b", "a?", "a*", "a[b", "a\\b", "x.lock", ".hidden", "a//b", "a/", "/a",
                "a.", "@", "a@{b")) {
            assertThat(GitRefNames.isValid(ref)).as("step 4: '" + ref + "' is refused").isFalse();
        }

        // 5. The runner refuses the same at construction (defence in depth): a remote-only
        //    repository will not take a path, and no repository takes an option as branch.
        assertThat(catchThrowable(() -> new GitRepository("/srv/repos/app.git", "main", true,
                false, null)))
            .as("step 5: a remote-only repository refuses a controller path")
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new GitRepository("https://git.example.test/a.git",
                "--upload-pack=touch x", true, false, null)))
            .as("step 5: an option-shaped branch never reaches the argv")
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new GitRepository("/srv/repos/app.git", "main", true, false, null, true))
            .as("step 5: an operator-owned source may still clone a path").isNotNull();
    }

    /**
     * A local source is an OPERATOR's tool: the moment a tenant owns the record, the
     * checkout refuses it by name, before git runs.
     */
    @Test
    void aLocalSourceChecksOutForAnOperatorAndIsRefusedOnceATenantOwnsIt() throws Exception {
        Path upstream = Files.createTempDirectory("hohenheim-reach-upstream");
        git(upstream, "init", "-q", "-b", "main");
        git(upstream, "config", "user.email", "test@example.com");
        git(upstream, "config", "user.name", "Test");
        Files.writeString(upstream.resolve("index.html"), "reach");
        git(upstream, "add", ".");
        git(upstream, "commit", "-q", "-m", "reach");

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("repository_url", upstream.toString());
        settings.put("branch", "main");
        int applicationId = application("reach-app", settings);
        Path checkouts = Files.createTempDirectory("hohenheim-reach-checkouts");

        // 1. Operator-owned (nobody holds manage): the local path clones.
        String commit = GitCheckout.materialize(InstanceModel.MODEL_ID, applicationId, "main",
            settings, checkouts.resolve("operator").toFile());
        assertThat(commit).as("step 1: the operator's local source checked out")
            .matches("[0-9a-f]{40}");

        // 2. An option-shaped ref is refused by name and git never runs with it.
        File marker = checkouts.resolve("pwned").toFile();
        Throwable optionRef = catchThrowable(() -> GitCheckout.materialize(InstanceModel.MODEL_ID,
            applicationId, "--upload-pack=touch " + marker.getAbsolutePath(), settings,
            checkouts.resolve("option").toFile()));
        assertThat(optionRef).as("step 2: an option-shaped ref is a named refusal")
            .isInstanceOf(Violations.class).hasMessageContaining("source_ref_invalid");
        assertThat(marker).as("step 2: and nothing it asked for ran").doesNotExist();

        // 3. A tenant takes ownership: the SAME stored path is now refused at checkout.
        RecordGrants.grant(GrantSubjectType.USER, user("reach-tenant@hohenheim.local"),
            InstanceModel.MODEL_ID, applicationId, HohenheimAccess.MANAGE, true);
        File tenantCheckout = checkouts.resolve("tenant").toFile();
        Throwable local = catchThrowable(() -> GitCheckout.materialize(InstanceModel.MODEL_ID,
            applicationId, "main", settings, tenantCheckout));
        assertThat(local).as("step 3: a tenant-owned record cannot clone a controller path")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("source_repository_local_refused");
        assertThat(tenantCheckout).as("step 3: nothing was cloned").doesNotExist();

        // 4. The file:// spelling of the same place is the same refusal.
        Map<String, Object> fileUrl = new LinkedHashMap<>(settings);
        fileUrl.put("repository_url", "file://" + upstream);
        assertThat(catchThrowable(() -> GitCheckout.materialize(InstanceModel.MODEL_ID,
                applicationId, "main", fileUrl, tenantCheckout)))
            .as("step 4: file:// is a local source too")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("source_repository_local_refused");
    }

    /**
     * A tenant-owned provider's API calls reach public addresses only; the same base URL on
     * an operator-owned provider keeps working.
     */
    @Test
    void aTenantOwnedProviderCannotReachAPrivateAddress() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            int providerId = provider("Reach Forge", base);

            // 1. Operator-owned: a forge on the operator's own network is legitimate.
            List<GitProviderClient.RepoRef> repos = GitProviders.clientFor(providerId)
                .listRepositories();
            assertThat(repos).as("step 1: the operator-owned provider answered").isEmpty();
            assertThat(hits.get()).as("step 1: through a real request").isEqualTo(1);

            // 2. A tenant owns it now: the loopback address is refused BEFORE any connect,
            //    so a "Test connection" cannot probe the controller's own ports.
            RecordGrants.grant(GrantSubjectType.USER, user("reach-forge@hohenheim.local"),
                GitProviderModel.MODEL_ID, providerId, HohenheimAccess.MANAGE, true);
            Throwable refused = catchThrowable(() -> GitProviders.clientFor(providerId)
                .listRepositories());
            assertThat(refused).as("step 2: the tenant-owned provider is refused")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refused");
            assertThat(hits.get()).as("step 2: and nothing connected to the fake").isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    // -- fixtures -------------------------------------------------------------

    private static int application(String name, Map<String, Object> settings) {
        var instances = Models.get(InstanceModel.class);
        Row application = instances.createEmptyRow();
        application.set(InstanceModel.NAME, name);
        application.set(InstanceModel.KIND, "hohenheim:application");
        application.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        instances.save(application);
        return application.get(InstanceModel.ID);
    }

    private static int provider(String name, String baseUrl) {
        var providers = Models.get(GitProviderModel.class);
        Row row = providers.createEmptyRow();
        row.set(GitProviderModel.NAME, name);
        row.set(GitProviderModel.KIND, GiteaProviderKind.ID.toString());
        row.set(GitProviderModel.BASE_URL, baseUrl);
        row.set(GitProviderModel.SHARED, false);
        row.set(GitProviderModel.ACCESS_TOKEN, "token-" + name);
        providers.save(row);
        return row.get(GitProviderModel.ID);
    }

    private static int user(String email) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, email);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Now.instant());
        user.set(UserModel.UPDATED_AT, Now.instant());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static void git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(repo.toFile())
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
