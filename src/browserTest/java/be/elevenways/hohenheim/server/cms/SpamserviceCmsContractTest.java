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
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import be.elevenways.zenit.cms.common.action.ActionContext;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.schema.FilterState;
import be.elevenways.zenit.cms.common.schema.RangeFilterValue;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.cms.server.page.SettingsBackend;
import be.elevenways.zenit.common.data.RecordPage;
import be.elevenways.zenit.common.orm.field.DateField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
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
        List<PanelResource<?>> resources = List.of(
            SpamserviceClientsResource.create(() -> null),
            SpamserviceClientKeysResource.create(() -> null),
            SpamserviceSamplesResource.create(() -> null),
            SpamserviceSecurityEventsResource.create(() -> null),
            SpamserviceWordsResource.create(() -> null));

        for (PanelResource<?> resource : resources) {
            assertThat(resource.reads().isRows()).as(resource.slug()).isFalse();
            assertThat(resource.list().isStore()).as(resource.slug()).isTrue();
        }

        @SuppressWarnings("unchecked")
        PanelResource<ManagedClient> clients = (PanelResource<ManagedClient>) resources.get(0);
        TableView.Applied<ManagedClient> applied =
            TableView.forPrincipal(0, clients.id()).build().apply(clients.list().table());
        RecordPage<ManagedClient> page = clients.list().storePages().page(applied, AccessContext.anonymous());
        assertThat(page.rows()).isEmpty();
        assertThat(page.total())
            .as("a disconnected list totals the nothing it listed, never a negative total")
            .isZero();
        assertThat(clients.list().notice(AccessContext.anonymous())).isNotNull();
        assertThatThrownBy(() -> clients.reads().load().apply(UUID.randomUUID().toString(),
            AccessContext.anonymous())).isInstanceOf(SpamserviceApiException.class);

        String clientId = UUID.randomUUID().toString();
        ManagedClient client = new ManagedClient(clientId, "Primary", true, false, false, false,
            null, null, null, 50, null, null, null, "r1");
        assertThat(SpamserviceClientsResource.keysTarget(client).toUrl())
            .isEqualTo("/admin/spamservice-clients/" + clientId + "/page/keys");
        assertThat(resources.get(1).parent().subpageSlug()).isEqualTo(SpamserviceClientKeysResource.TAB);
    }

    @Test
    void remoteSchemasUseUuidTemporalAndSecretFields() {
        PanelResource<?> clients = SpamserviceClientsResource.create(() -> null);
        PanelResource<?> keys = SpamserviceClientKeysResource.create(() -> null);
        PanelResource<?> events = SpamserviceSecurityEventsResource.create(() -> null);
        PanelResource<?> samples = SpamserviceSamplesResource.create(() -> null);

        assertThat(field(clients, "provisioned_by_client_id")).isInstanceOf(UuidField.class);
        assertThat(field(keys, "client_id")).isInstanceOf(UuidField.class);
        assertThat(field(keys, "last_used")).isInstanceOf(DateTimeField.class);
        assertThat(field(keys, "created_at")).isInstanceOf(DateTimeField.class);
        assertThat(field(keys, "key").isSecret()).isTrue();
        assertThat(field(events, "client_id")).isInstanceOf(UuidField.class);
        assertThat(field(events, "day")).isInstanceOf(DateField.class);
        assertThat(field(events, "first_at")).isInstanceOf(DateTimeField.class);
        assertThat(field(events, "last_at")).isInstanceOf(DateTimeField.class);
        assertThat(field(samples, "client_id")).isInstanceOf(UuidField.class);
        assertThat(field(samples, "created_at")).isInstanceOf(DateTimeField.class);
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
                      {"path":"network.port","label":"Port","description":null,"type":"integer","secret":false,"multiline":false,"suffix":null,"filesystem_path":false,"restart_required":false,"configured":true,"readonly":true,"source":"env:PORT","value":8095,"has_secret":false,"default_value":8095,"allowed_values":[]},
                      {"path":"reputation.refresh_days","label":"Refresh interval","description":null,"type":"integer","secret":false,"multiline":false,"suffix":null,"filesystem_path":false,"restart_required":false,"configured":false,"readonly":false,"source":"default","value":15,"has_secret":false,"default_value":15,"allowed_values":[]},
                      {"path":"events.retention_days","label":"Retention","description":null,"type":"integer","secret":false,"multiline":false,"suffix":null,"filesystem_path":false,"restart_required":false,"configured":false,"readonly":false,"source":"default","value":90,"has_secret":false,"default_value":90,"allowed_values":[]}
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

        // The host owns group copy only: labels localize without changing remote definitions or snapshot facts.
        var scoring = snapshot.rootGroup().getChildGroup("scoring");
        assertThat(snapshot.rootGroup().displayLabel().key()).isEqualTo("settings.spamservice.label");
        assertThat(scoring.displayLabel().key()).isEqualTo("settings.spamservice.scoring.label");
        ShippedCatalogs catalogs = new ShippedCatalogs();
        assertThat(scoring.displayLabel().resolve(LocaleChain.ofTags("en"), catalogs)).isEqualTo("Scoring");
        assertThat(scoring.displayLabel().resolve(LocaleChain.ofTags("nl"), catalogs)).isEqualTo("Scoring");
        assertThat(snapshot.rootGroup().displayDescription().key()).isEqualTo("settings.spamservice.help");
        assertThat(scoring.displayDescription().key()).isEqualTo("settings.spamservice.scoring.help");
        assertThat(scoring.displayDescription().resolve(LocaleChain.ofTags("en"), catalogs))
            .isEqualTo("Spam verdict thresholds");
        assertThat(scoring.displayDescription().resolve(LocaleChain.ofTags("nl"), catalogs))
            .isEqualTo("Drempels voor spamverdicts");
        for (String name : List.of("scoring", "reputation", "events")) {
            var description = snapshot.rootGroup().getChildGroup(name).displayDescription();
            assertThat(description.key()).isEqualTo("settings.spamservice." + name + ".help");
            assertThat(description.fallback()).as("group copy is declared, not a remote-data fallback").isNull();
            for (String language : List.of("en", "nl")) {
                assertThat(catalogs.resolveSource(description.key(), LocaleChain.ofTags(language), description.filters()))
                    .as("%s group description is shipped in %s", name, language).isNotNull();
            }
        }
        assertThat(snapshot.rootGroup().getChildGroup("datasets").displayLabel().fallback())
            .as("an unknown remote group retains its offered fallback title").isEqualTo("Datasets");
        assertThat(snapshot.rootGroup().getChildGroup("datasets").displayDescription())
            .as("an unknown remote group has no invented description identity").isNull();
        assertThat(scoring.getDefinition("threshold").getLabel()).isEqualTo("Threshold");
        assertThat(scoring.getDefinition("threshold").getDescription()).isEqualTo("Cutoff");
        assertThat(scoring.isAdvanced()).as("translated labels do not change remote grouping facts").isFalse();

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
        PanelResource<ManagedClient> resource = SpamserviceClientsResource.create(() -> client);

        resource.writes().storeUpdate().update(existing, Map.of(
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
        SpamserviceClientsResource.create(() -> client).writes().storeUpdate().update(existing,
            Map.of("name", "Renamed"), AccessContext.anonymous());

        assertThat(body.get()).as("step 1: the rename is the only field that moved")
            .contains("\"name\":\"Renamed\"").contains("\"enabled\":true")
            .contains("\"trusted\":true").contains("\"provisioner\":true")
            .contains("eng,nld").contains("\"spam_threshold\":80").contains("keep this note");

        // 2. Same for a spam word: score, language and leet survive a correction of the
        //    word itself.
        SpamserviceWordsResource.create(() -> client).writes().storeUpdate().update(
            new SpamWordEntry(wordId, "viagraa", 70, "eng", true, null, null),
            Map.of("word", "viagra"), AccessContext.anonymous());

        assertThat(body.get()).as("step 2: the word's score, language and leet flag survive")
            .contains("\"word\":\"viagra\"").contains("\"score\":70")
            .contains("eng").contains("\"leet\":true");

        // 3. And a key write that carries no name must not rename the key to "null".
        SpamserviceClientKeysResource.create(() -> client).writes().storeUpdate().update(
            new ManagedClientKey(keyId, clientId, "primary", true, null, null),
            Map.of("active", false), AccessContext.anonymous());

        assertThat(body.get()).as("step 3: an absent name is sent as absent, never as \"null\"")
            .doesNotContain("\"name\":\"null\"");

        // 4. The SAME shape one file over, which the fix above did not reach: a client whose
        //    stored name is null, edited by a write that carries no name. The fallback is the
        //    null itself, and String.valueOf over it is the four characters "null" -- PUT to
        //    the live filter as the client's new name.
        SpamserviceClientsResource.create(() -> client).writes().storeUpdate().update(
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
        SpamserviceClientsResource.create(() -> client).writes().storeUpdate().update(existing, blankName,
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
        PanelResource<SecurityEventEntry> resource = SpamserviceSecurityEventsResource.create(() -> client);

        SecurityEventEntry event = resource.reads().load().apply(eventId, AccessContext.anonymous());

        assertThat(event).isNotNull();
        assertThat(event.day()).isEqualTo(LocalDate.parse("2026-07-23"));
        assertThat(event.lastAt()).isEqualTo(Instant.parse("2026-07-23T01:00:00Z"));
        assertThat(path.get()).isEqualTo("/v1/manage/security-events/" + eventId);
    }

    /**
     * BEHAVIOUR journey: the list search and the day leaf reach the management API as its own q and from/to
     * parameters, and every store declares the fields its filters name.
     */
    @Test
    void listSearchAndDayLeafRideTheManagementApisOwnParameters() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        SpamserviceClient client = client(exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            return "{\"items\":[],\"page\":1,\"page_size\":25,\"total\":0}";
        });

        // 1. A clients search is forwarded as the API's q, beside the enabled filter it always sent.
        PanelResource<ManagedClient> clients = SpamserviceClientsResource.create(() -> client);
        assertThat(clients.list().searchColumns()).as("step 1: the API searches client names").containsExactly("name");
        TableView.Applied<ManagedClient> clientView = TableView.forPrincipal(0, clients.id()).build()
            .apply(clients.list().table()).withSearch("  prim ")
            .withFilter(FilterState.of(Map.of("enabled", "true")));
        clients.list().storePages().page(clientView, AccessContext.anonymous());
        assertThat(parameters(query.get())).as("step 1: q carries the trimmed search")
            .containsEntry("q", "prim").containsEntry("enabled", "true");

        // 2. A words search is forwarded as the API's q.
        PanelResource<SpamWordEntry> words = SpamserviceWordsResource.create(() -> client);
        assertThat(words.list().searchColumns()).as("step 2: the API searches words").containsExactly("word");
        words.list().storePages().page(TableView.forPrincipal(0, words.id()).build().apply(words.list().table())
            .withSearch("viagra"), AccessContext.anonymous());
        assertThat(parameters(query.get())).as("step 2: q carries the search").containsEntry("q", "viagra");

        // 3. The day leaf's bounds become the API's from/to; a lone lower bound sends only from.
        PanelResource<SecurityEventEntry> events = SpamserviceSecurityEventsResource.create(() -> client);
        TableView.Applied<SecurityEventEntry> eventView = TableView.forPrincipal(0, events.id()).build()
            .apply(events.list().table());
        events.list().storePages().page(eventView.withFilter(FilterState.of(Map.of(
            "day", new RangeFilterValue("2026-07-01", "2026-07-31")))), AccessContext.anonymous());
        assertThat(parameters(query.get())).as("step 3: both bounds")
            .containsEntry("from", "2026-07-01").containsEntry("to", "2026-07-31");
        events.list().storePages().page(eventView.withFilter(FilterState.of(Map.of(
            "day", new RangeFilterValue("2026-07-01", null)))), AccessContext.anonymous());
        assertThat(parameters(query.get())).as("step 3: a lone lower bound")
            .containsEntry("from", "2026-07-01").doesNotContainKey("to");

        // 4. No search and no bound sends neither parameter, as the unfiltered list always asked.
        events.list().storePages().page(eventView, AccessContext.anonymous());
        assertThat(parameters(query.get())).as("step 4: an unfiltered list")
            .doesNotContainKeys("q", "from", "to").containsKey("page_size");
    }

    private static Map<String, String> parameters(String rawQuery) {
        Map<String, String> result = new java.util.HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            result.put(pair.substring(0, equals), java.net.URLDecoder.decode(pair.substring(equals + 1),
                StandardCharsets.UTF_8));
        }
        return result;
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

        PanelResource<ManagedClientKey> keys = SpamserviceClientKeysResource.create(() -> client);
        Object createdKey = keys.writes().storeCreateUnder().create(clientId,
            Map.of("client_id", UUID.fromString(clientId), "name", "primary", "key", ""), AccessContext.anonymous());
        assertThat(createdKey).isEqualTo(clientId + "~" + keyId);
        assertThat(keys.reads().values().apply(new ManagedClientKey(keyId, clientId, "primary", true, null,
            Instant.parse("2026-07-23T00:00:00Z"))))
            .containsEntry("key", "").doesNotContainValue("spam_once");

        SampleSummary sample = new SampleSummary(sampleId, clientId, "203.0.113.9", false,
            10, false, "", "eng", null, null);
        SpamserviceSamplesResource.markSpam(() -> client, sample);
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

    private static Field<?, ?> field(PanelResource<?> resource, String name) {
        return resource.form().spec().entries().stream().map(entry -> entry.field())
            .filter(field -> field.getName().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError(resource.slug() + " has no form field " + name));
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
