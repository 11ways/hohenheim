package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.spamservice.client.ManagedClient;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.SampleSummary;
import be.elevenways.spamservice.client.SecurityEventEntry;
import be.elevenways.spamservice.client.SpamserviceApiException;
import be.elevenways.spamservice.client.SpamWordEntry;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionContext;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.cms.server.page.SettingsBackend;
import be.elevenways.zenit.common.orm.field.DateField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.security.AccessContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract tests for Hohenheim's model-independent Spamservice administration. */
class SpamserviceCmsContractTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (this.server != null) this.server.stop(0);
    }

    @Test
    void resourcesAreRemoteOnlyAndDisconnectedListsStayUsable() {
        List<Resource<?>> resources = List.of(
            new SpamserviceClientsResource(() -> null),
            new SpamserviceClientKeysResource(() -> null),
            new SpamserviceSamplesResource(() -> null),
            new SpamserviceSecurityEventsResource(() -> null),
            new SpamserviceWordsResource(() -> null));

        for (Resource<?> resource : resources) {
            assertThat(resource.model()).as(resource.slug()).isNull();
        }

        SpamserviceClientsResource clients = (SpamserviceClientsResource) resources.get(0);
        TableView.Applied<be.elevenways.spamservice.client.ManagedClient> applied =
            TableView.forPrincipal(0, clients.id()).build().apply(clients.tableSpec());
        assertThat(clients.listRows(applied, AccessContext.anonymous())).isEmpty();
        assertThat(clients.countRows(applied, AccessContext.anonymous())).isEqualTo(-1);
        assertThat(clients.listNotice(AccessContext.anonymous())).isNotNull();
        assertThatThrownBy(() -> clients.loadRow(UUID.randomUUID(), AccessContext.anonymous()))
            .isInstanceOf(SpamserviceApiException.class);

        String clientId = UUID.randomUUID().toString();
        ManagedClient client = new ManagedClient(clientId, "Primary", true, false, false, false,
            null, null, null, 50, null, null, null, "r1");
        RowAction.Url<ManagedClient> keysAction = (RowAction.Url<ManagedClient>) clients.rowActions().get(0);
        assertThat(keysAction.urlFor(client).toString())
            .isEqualTo("/admin/spamservice-clients/" + clientId + "/page/keys");
        assertThat(((SpamserviceClientKeysResource) resources.get(1)).parent().subpageSlug())
            .isEqualTo(SpamserviceClientKeysPage.SLUG);
    }

    @Test
    void remoteSchemasUseUuidTemporalAndSecretFields() {
        SpamserviceClientsResource clients = new SpamserviceClientsResource(() -> null);
        SpamserviceClientKeysResource keys = new SpamserviceClientKeysResource(() -> null);
        SpamserviceSecurityEventsResource events = new SpamserviceSecurityEventsResource(() -> null);
        SpamserviceSamplesResource samples = new SpamserviceSamplesResource(() -> null);

        assertThat(clients.schema().getField("provisioned_by_client_id")).isInstanceOf(UuidField.class);
        assertThat(keys.schema().getField("client_id")).isInstanceOf(UuidField.class);
        assertThat(keys.schema().getField("last_used")).isInstanceOf(DateTimeField.class);
        assertThat(keys.schema().getField("created_at")).isInstanceOf(DateTimeField.class);
        assertThat(keys.schema().getField("key").isSecret()).isTrue();
        assertThat(events.schema().getField("client_id")).isInstanceOf(UuidField.class);
        assertThat(events.schema().getField("day")).isInstanceOf(DateField.class);
        assertThat(events.schema().getField("first_at")).isInstanceOf(DateTimeField.class);
        assertThat(events.schema().getField("last_at")).isInstanceOf(DateTimeField.class);
        assertThat(samples.schema().getField("client_id")).isInstanceOf(UuidField.class);
        assertThat(samples.schema().getField("created_at")).isInstanceOf(DateTimeField.class);
    }

    @Test
    void installationUsesTypedDefaultsAndNeverRendersControllerKey() {
        SpamserviceInstallationResource installation = new SpamserviceInstallationResource();
        List<String> fields = installation.formSpec().entries().stream()
            .map(entry -> entry.field().getName()).toList();

        assertThat(fields).containsExactly("enabled", "port", "system_user_id", "max_heap_mb");
        assertThat(fields).doesNotContain(SpamserviceInstallationModel.CONTROLLER_KEY.getName());
        assertThat(installation.formSpec().defaultValues())
            .containsEntry("enabled", false).containsEntry("port", 8095).containsEntry("max_heap_mb", 512);
    }

    @Test
    void remoteSettingsPreserveSecretsProvenanceRevisionAndRestartFlag() throws Exception {
        AtomicReference<String> patchBody = new AtomicReference<>();
        SpamserviceClient client = client(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                return """
                    {"revision":"r1","settings":[
                      {"path":"scoring.threshold","label":"Threshold","description":"Cutoff","type":"integer","secret":false,"multiline":false,"suffix":"points","filesystem_path":false,"restart_required":true,"configured":true,"readonly":false,"source":"settings/spamservice.dry","value":50,"has_secret":false,"default_value":40,"allowed_values":[]},
                      {"path":"datasets.token","label":"Token","description":null,"type":"string","secret":true,"multiline":false,"suffix":null,"filesystem_path":false,"restart_required":false,"configured":true,"readonly":false,"source":"settings/spamservice.dry","value":null,"has_secret":true,"default_value":null,"allowed_values":[]},
                      {"path":"network.port","label":"Port","description":null,"type":"integer","secret":false,"multiline":false,"suffix":null,"filesystem_path":false,"restart_required":false,"configured":true,"readonly":true,"source":"env:PORT","value":8095,"has_secret":false,"default_value":8095,"allowed_values":[]}
                    ]}
                    """;
            }
            patchBody.set(readBody(exchange));
            return "{\"revision\":\"r2\",\"changed\":1,\"restart_required\":true}";
        });
        SpamserviceSettingsBackend backend = new SpamserviceSettingsBackend(() -> client);

        SettingsBackend.Snapshot snapshot = backend.snapshot();
        assertThat(snapshot.revision()).isEqualTo("r1");
        assertThat(snapshot.settings().get("datasets.token").value()).isNull();
        assertThat(snapshot.settings().get("datasets.token").secretPresent()).isTrue();
        assertThat(snapshot.settings().get("network.port").readOnly()).isTrue();
        assertThat(snapshot.settings().get("network.port").provenance()).isEqualTo("env:PORT");
        assertThat(snapshot.rootGroup().getChildGroup("scoring").getDefinition("threshold").isRestartRequired()).isTrue();

        assertThat(backend.validate(new SettingsBackend.Patch("r1", List.of(
            SettingsBackend.Change.set("network.port", "9000")))))
            .extracting(SettingsBackend.Refusal::kind).containsExactly(SettingsBackend.RefusalKind.READ_ONLY);

        SettingsBackend.ApplyResult result = backend.apply(new SettingsBackend.Patch("r1", List.of(
            SettingsBackend.Change.set("scoring.threshold", "60"))));
        assertThat(result.succeeded()).isTrue();
        assertThat(result.restartRequired()).isTrue();
        assertThat(result.revision()).isEqualTo("r2");
        assertThat(patchBody.get()).contains("scoring.threshold").contains("60").doesNotContain("secret-value");

        this.server.stop(0);
        this.server = null;
        SettingsBackend.Snapshot unavailable = backend.snapshot();
        assertThat(unavailable.available()).isFalse();
        assertThat(unavailable.settings()).containsKeys("scoring.threshold", "datasets.token", "network.port");
        assertThat(backend.apply(new SettingsBackend.Patch("r1", List.of(
            SettingsBackend.Change.set("scoring.threshold", "70")))).succeeded()).isFalse();
    }

    @Test
    void clientUpdatesUseRevisionGuardedPutWithoutProvisioningMetadata() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        String clientId = UUID.randomUUID().toString();
        SpamserviceClient client = client(exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(readBody(exchange));
            return "{\"id\":\"" + clientId + "\",\"name\":\"Renamed\",\"enabled\":true,"
                + "\"trusted\":true,\"provisioner\":false,\"manager\":false,\"external_id\":\"owned\","
                + "\"provisioned_by_client_id\":null,\"allowed_languages\":\"eng\",\"spam_threshold\":55,"
                + "\"notes\":\"note\",\"created_at\":null,\"updated_at\":null,\"revision\":\"r2\"}";
        });
        ManagedClient existing = new ManagedClient(clientId, "Original", true, true, false, false,
            "owned", null, "eng", 50, null, null, null, "r1");
        SpamserviceClientsResource resource = new SpamserviceClientsResource(() -> client);

        resource.updateRow(existing, Map.of(
            "name", "Renamed", "enabled", true, "trusted", true, "provisioner", false,
            "manager", false, "allowed_languages", "eng", "spam_threshold", 55, "notes", "note"),
            AccessContext.anonymous());

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(body.get()).contains("\"revision\":\"r1\"")
            .doesNotContain("external_id").doesNotContain("provisioned_by_client_id");
    }

    /**
     * Steps 1-3: a one-entry write never blanks the rest of a remote record.
     *
     * AIDEV-NOTE: these three resources rebuild a FULL remote DTO from the coerced map, and
     * the inline cell lane hands updateRow a map holding EXACTLY ONE entry -- so a rename
     * used to PUT a disabled client with no language whitelist and a reset threshold to the
     * live spam filter. The fix fills every field the write does not carry from the STORED
     * record rather than assuming the remote API treats absence as unchanged; its source is
     * outside this workspace and promises no such thing. The one place absence IS a
     * documented "leave alone" is updateKey's nullable arguments, which the enable/revoke
     * row actions already rely on.
     */
    @Test
    void aOneEntryWriteKeepsEveryRemoteFieldItDoesNotCarry() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String clientId = UUID.randomUUID().toString();
        String wordId = UUID.randomUUID().toString();
        String keyId = UUID.randomUUID().toString();
        SpamserviceClient client = client(exchange -> {
            body.set(readBody(exchange));
            return "{\"id\":\"" + clientId + "\",\"name\":\"Renamed\",\"enabled\":true,"
                + "\"trusted\":true,\"provisioner\":true,\"manager\":false,\"external_id\":\"owned\","
                + "\"provisioned_by_client_id\":null,\"allowed_languages\":\"eng,nld\","
                + "\"spam_threshold\":80,\"notes\":\"keep this note\",\"created_at\":null,"
                + "\"updated_at\":null,\"revision\":\"r2\"}";
        });

        // 1. A trusted, provisioning client with a language whitelist and a raised
        //    threshold -- every one of them a setting a blank PUT would silently drop.
        ManagedClient existing = new ManagedClient(clientId, "Original", true, true, true, false,
            "owned", null, "eng,nld", 80, "keep this note", null, null, "r1");
        new SpamserviceClientsResource(() -> client).updateRow(existing,
            Map.of("name", "Renamed"), AccessContext.anonymous());

        assertThat(body.get()).as("step 1: the rename is the only field that moved")
            .contains("\"name\":\"Renamed\"").contains("\"enabled\":true")
            .contains("\"trusted\":true").contains("\"provisioner\":true")
            .contains("eng,nld").contains("\"spam_threshold\":80").contains("keep this note");

        // 2. Same for a spam word: score, language and leet survive a correction of the
        //    word itself.
        new SpamserviceWordsResource(() -> client).updateRow(
            new SpamWordEntry(wordId, "viagraa", 70, "eng", true, null, null),
            Map.of("word", "viagra"), AccessContext.anonymous());

        assertThat(body.get()).as("step 2: the word's score, language and leet flag survive")
            .contains("\"word\":\"viagra\"").contains("\"score\":70")
            .contains("eng").contains("\"leet\":true");

        // 3. And a key write that carries no name must not rename the key to "null".
        new SpamserviceClientKeysResource(() -> client).updateRow(
            new ManagedClientKey(keyId, clientId, "primary", true, null, null),
            Map.of("active", false), AccessContext.anonymous());

        assertThat(body.get()).as("step 3: an absent name is sent as absent, never as \"null\"")
            .doesNotContain("\"name\":\"null\"");

        // 4. The SAME shape one file over, which the fix above did not reach: a client whose
        //    stored name is null, edited by a write that carries no name. The fallback is the
        //    null itself, and String.valueOf over it is the four characters "null" -- PUT to
        //    the live filter as the client's new name.
        new SpamserviceClientsResource(() -> client).updateRow(
            new ManagedClient(clientId, null, true, false, false, false, "owned", null, "eng",
                50, null, null, null, "r1"),
            Map.of("enabled", true), AccessContext.anonymous());

        assertThat(body.get())
            .as("step 4: a client with no stored name is never renamed to the text \"null\"")
            .doesNotContain("\"name\":\"null\"");

        // 5. And the same for a name the write DOES carry as null, which is what a blank
        //    submitted entry coerces to -- getOrDefault answers null for a present key.
        Map<String, Object> blankName = new java.util.HashMap<>();
        blankName.put("name", null);
        new SpamserviceClientsResource(() -> client).updateRow(existing, blankName,
            AccessContext.anonymous());

        assertThat(body.get())
            .as("step 5: a submitted blank name is not the text \"null\" either")
            .doesNotContain("\"name\":\"null\"");
    }

    @Test
    void securityEventDetailUsesTheDirectTypedEndpoint() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        String eventId = UUID.randomUUID().toString();
        String clientId = UUID.randomUUID().toString();
        SpamserviceClient client = client(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            return "{\"id\":\"" + eventId + "\",\"client_id\":\"" + clientId
                + "\",\"type\":\"auth.failed\",\"ip\":\"203.0.113.4\",\"day\":\"2026-07-23\","
                + "\"count\":2,\"first_at\":\"2026-07-23T00:00:00Z\","
                + "\"last_at\":\"2026-07-23T01:00:00Z\",\"last_detail\":null}";
        });
        SpamserviceSecurityEventsResource resource = new SpamserviceSecurityEventsResource(() -> client);

        SecurityEventEntry event = resource.loadRow(UUID.fromString(eventId), AccessContext.anonymous());

        assertThat(event).isNotNull();
        assertThat(event.day()).isEqualTo(LocalDate.parse("2026-07-23"));
        assertThat(event.lastAt()).isEqualTo(Instant.parse("2026-07-23T01:00:00Z"));
        assertThat(path.get()).isEqualTo("/v1/manage/security-events/" + eventId);
    }

    @Test
    void generatedKeysAreCreateOnlyAndSampleActionsUseStrictManagementCalls() throws Exception {
        AtomicReference<String> lastPath = new AtomicReference<>();
        String clientId = UUID.randomUUID().toString();
        String keyId = UUID.randomUUID().toString();
        String sampleId = UUID.randomUUID().toString();
        SpamserviceClient client = client(exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            if (lastPath.get().endsWith("/keys")) {
                return "{\"id\":\"" + keyId + "\",\"client_id\":\"" + clientId
                    + "\",\"name\":\"primary\",\"key\":\"spam_once\",\"generated\":true}";
            }
            return "{\"id\":\"" + sampleId + "\",\"client_id\":\"" + clientId
                + "\",\"ip\":\"203.0.113.9\",\"spam\":true,\"score\":70,\"confirmed\":true,"
                + "\"flags\":\"\",\"languages\":\"eng\",\"created_at\":\"2026-07-23T00:00:00Z\","
                + "\"updated_at\":null,\"useragent\":null,\"heuristic_score\":70,\"threshold\":50,"
                + "\"confirmed_origin\":\"manual\",\"location\":{},\"asn\":{},\"properties\":[],\"breakdown\":[]}";
        });

        SpamserviceClientKeysResource keys = new SpamserviceClientKeysResource(() -> client);
        Object createdKey = keys.persistRow(Map.of("client_id", UUID.fromString(clientId), "name", "primary", "key", ""),
            AccessContext.anonymous());
        assertThat(createdKey).isEqualTo(clientId + "~" + keyId);
        assertThat(keys.valuesFromRow(new ManagedClientKey(keyId, clientId, "primary", true, null,
            Instant.parse("2026-07-23T00:00:00Z"))))
            .containsEntry("key", "").doesNotContainValue("spam_once");

        SpamserviceSamplesResource samples = new SpamserviceSamplesResource(() -> client);
        SampleSummary sample = new SampleSummary(sampleId, clientId, "203.0.113.9", false,
            10, false, "", "eng", null, null);
        ((RowAction.Invoke<SampleSummary>) samples.rowActions().get(0))
            .invoke(sample, ActionContext.of(AccessContext.anonymous()));
        assertThat(lastPath.get()).isEqualTo("/v1/manage/samples/" + sampleId + "/mark-spam");
    }

    /**
     * BEHAVIOUR journey: on a control plane where Spamservice was never configured, every
     * lifecycle action must SAY so -- rendered dead with the reason and refused with it --
     * instead of offering a live Stop button whose only possible answer is a generic failure.
     */
    @Test
    void installationLifecycleActionsNameTheStateThatBlocksThem() {
        SpamserviceInstallationResource installation = new SpamserviceInstallationResource();
        AccessContext context = AccessContext.anonymous();

        // 1. All four lifecycle verbs are header invokes, in the order an operator meets them.
        List<HeaderAction> actions = installation.headerActions();
        assertThat(actions).hasSize(4).allSatisfy(action ->
            assertThat(action).isInstanceOf(HeaderAction.Invoke.class));
        assertThat(actions.stream().map(action -> action.id().getPath()).toList())
            .containsExactly("spamservice_start", "spamservice_stop", "spamservice_restart",
                "spamservice_test");

        // 2. Nothing is configured in this JVM, so every one of them declares the SAME
        //    root state rather than a per-action guess.
        for (HeaderAction action : actions) {
            Microcopy reason = ((HeaderAction.Invoke) action).unavailableReason(context);
            assertThat(reason).as("%s declares a reason", action.id()).isNotNull();
            assertThat(reason.key()).as("%s names the unconfigured state", action.id())
                .isEqualTo("not_configured");
        }

        // 3. Test connection REFUSES with that reason as an error toast -- never the
        //    generic cms.action.failed the operator cannot act on.
        HeaderAction.Invoke test = (HeaderAction.Invoke) actions.get(3);
        CmsActionResult result = test.invoke(ActionContext.of(context));
        assertThat(result).isInstanceOf(CmsActionResult.Toast.class);
        CmsActionResult.Toast toast = (CmsActionResult.Toast) result;
        assertThat(toast.message().key()).isEqualTo("not_configured");
    }

    private SpamserviceClient client(Function<HttpExchange, String> responder) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> respond(exchange, responder.apply(exchange)));
        this.server.start();
        return SpamserviceClient.builder("http://127.0.0.1:" + this.server.getAddress().getPort(), "test-key").build();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read test request", failure);
        }
    }
}
