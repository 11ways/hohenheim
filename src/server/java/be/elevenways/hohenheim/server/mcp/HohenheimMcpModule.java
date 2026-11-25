package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.instance.InstanceLogOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.*;
import be.elevenways.hohenheim.server.HohenheimHostWiring;
import be.elevenways.hohenheim.server.cms.*;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.source.GitProviderOperations;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.ai.server.mcp.host.McpDescribeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpListTool;
import be.elevenways.zenit.ai.server.mcp.host.McpRecordTools;
import be.elevenways.zenit.ai.server.mcp.host.McpToolSet;
import be.elevenways.zenit.auth.server.mcp.AuthMcp;
import be.elevenways.zenit.auth.server.mcp.AuthTools;
import be.elevenways.zenit.common.ZenitModule;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.operation.AssistantNoun;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationRunModel;
import be.elevenways.zenit.common.orm.code.RecordCodeBehaviour;
import be.elevenways.zenit.common.orm.code.RecordCodeKinds;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.server.operation.OperationRunOperations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Hohenheim's one assistant connection, {@code /zn/mcp/hohenheim}: its record sources listed by the one {@code list}
 * tool, its operations placed under the names their verbs and nouns derive (a background one answering its run's
 * code, followed with {@code run_read}), and zenit-auth's account tools, admitted by a key with the scope
 * {@code mcp:hohenheim}, which the API keys page offers as the "Hohenheim assistant" preset.
 *
 * AIDEV-NOTE: nothing here names a tool, a permission or a scope: the names derive from each operation's verb and noun
 * (McpToolSet), every call is the operation's own gate and authorizer, and the preset is zenit-ai's. An operation or
 * source missing from these lists is a gap HohenheimMcpTest pins; one an assistant must never get declares why on
 * itself (notForAssistants), not here. zenit-auth's tools are this server's own collection, not an included server:
 * its lists join {@code list}, and no {@code /zn/mcp/auth} route is registered beside this one.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HohenheimMcpModule implements ZenitModule {

    /** The server's name: its route is {@code /zn/mcp/hohenheim}, and a key admits it with {@code mcp:hohenheim}. */
    public static final String SERVER_NAME = "hohenheim";

    /** The tools {@link #install()} built, for the coverage check. */
    private static volatile @Nullable McpToolSet tools;

    /** The hand-declared tools {@link #install()} built, for the coverage check. */
    private static volatile @Nullable HohenheimTools declared;

    /**
     * The models whose sources the server lists, in the order its tools are offered. Deploys and builds are not among
     * them: app_list_deploys lists an app's history, both merged, newest first (KeptFromAssistants.DEPLOY_HISTORY).
     * Variables carry no code: they are listed by their app ({@code list kind=variable of=AP12}), read by whoever may
     * view it, as their declared visibility says (HohenheimGrantPolicy).
     */
    private static final List<Identifier> LISTED = List.of(SiteModel.MODEL_ID, SiteDomainModel.MODEL_ID,
        ProtectedPathModel.MODEL_ID, SiteRedirectModel.MODEL_ID, AccessListModel.MODEL_ID, AccessRuleModel.MODEL_ID,
        CertificateModel.MODEL_ID, InstanceModel.MODEL_ID, InstanceTemplateModel.MODEL_ID,
        InstanceDeviceModel.MODEL_ID, InstanceVolumeModel.MODEL_ID, InstanceDatabaseModel.MODEL_ID,
        InstanceVariableModel.MODEL_ID, InstanceBackupModel.MODEL_ID, InstanceSnapshotModel.MODEL_ID,
        PreviewDeploymentModel.MODEL_ID, ProjectModel.MODEL_ID, DatabaseModel.MODEL_ID, DatabaseEngineModel.MODEL_ID,
        DatabaseDumpModel.MODEL_ID, GitProviderModel.MODEL_ID, HostModel.MODEL_ID, RuntimeImageModel.MODEL_ID,
        BackupTargetModel.MODEL_ID, SystemUserModel.MODEL_ID, StackModel.MODEL_ID, DnsZoneModel.MODEL_ID,
        DnsPeerModel.MODEL_ID, StackServiceModel.MODEL_ID, EnvironmentModel.MODEL_ID, GameDomainModel.MODEL_ID,
        InstanceFileModel.MODEL_ID, InstanceTemplateFileModel.MODEL_ID, StackFileModel.MODEL_ID,
        RecordScheduleModel.MODEL_ID, RecordScheduleRunModel.MODEL_ID, SiteAuthProviderModel.MODEL_ID);

    @Override
    public @NonNull Set<String> initializesAfter() {
        // The sources exist once these ran: the common registrations, then the server-side overrides and admin ones.
        return Set.of(HohenheimSources.class.getName(), HohenheimHostWiring.class.getName());
    }

    @Override
    public void init() {
        install();
        fillCodes();
    }

    /**
     * Gives every record of a Hohenheim model written before its model declared its code kind its code; idempotent.
     *
     * AIDEV-NOTE: the kinds are read from RecordCodeKinds, the one vocabulary of them, so a model that declares a code
     * is filled without being named here.
     */
    public static void fillCodes() {
        for (Identifier model : RecordCodeKinds.models()) {
            if (!HohenheimIds.PRODUCT.namespace().equals(model.getNamespace())) continue;
            Model bound = Models.get(model);
            if (bound != null && RecordCodeKinds.of(model) instanceof RecordCodeBehaviour.Allocated kind) {
                kind.fillMissing(bound);
            }
        }
    }

    /** @return Hohenheim's own derived tools, null before {@link #install()}: what its coverage is judged against */
    public static @Nullable McpToolSet tools() {
        return tools;
    }

    /** @return Hohenheim's hand-declared tools, null before {@link #install()} */
    public static @Nullable HohenheimTools declared() {
        return declared;
    }

    /** Registers the server once per process; a second call changes nothing. */
    public static synchronized void install() {
        if (McpHostServers.get(SERVER_NAME) != null) return;
        List<RecordSource<?>> sources = new ArrayList<>();
        Map<Identifier, RecordSource<?>> ownLists = ownLists();
        for (Identifier model : LISTED) {
            // A source a role leaves out (backup targets without the instance role) is no tool there.
            RecordSource<?> source = RecordSourceRegistry.INSTANCE.declaredFor(model);
            if (source == null) source = ownLists.get(model);
            if (source != null) sources.add(source);
        }
        // AIDEV-NOTE: an operation a hand-declared tool routes (HohenheimTools) is placed there and never here, so
        // each is reached by exactly one tool and argument value (HohenheimMcpTest pins it).
        List<Operation<?, ?, ?>> placed = new ArrayList<>(List.of(
            InstanceOperations.CONNECT_WEBHOOK,
            InstanceOperations.ATTACH_INSTALL_MEDIA, InstanceOperations.DETACH_INSTALL_MEDIA,
            InstanceTemplateOperations.CREATE_INSTANCE_FROM_TEMPLATE,
            SiteOperations.ENABLE, SiteOperations.DISABLE, SiteOperations.CLONE,
            SpamserviceOperations.START, SpamserviceOperations.STOP, SpamserviceOperations.RESTART));
        // Every tool of the server names its record by code, as the hand-declared ones do (SPEC-RULES "Parameters").
        McpToolSet.Builder set = McpToolSet.builder().namesRecordsByCode()
            .lists(sources.toArray(new RecordSource<?>[0]))
            .places(placed.toArray(new Operation<?, ?, ?>[0]))
            .answersWith(StackOperations.REFRESH, HohenheimMcpAnswers::output)
            .answersWith(SpamserviceOperations.TEST, HohenheimMcpAnswers::output)
            .answersWith(GitProviderOperations.TEST_CONNECTION, HohenheimMcpAnswers::connection)
            .answersWith(NotificationChannelParts.TEST, HohenheimMcpAnswers::delivered)
            .answersWith(DnsOperations.NEGOTIATE_KEY, HohenheimMcpAnswers::told)
            .answersWith(InstanceLogOperations.LIST_DEPLOYS, HohenheimMcpAnswers::deploys);
        tools = set.build();
        HohenheimTools hand = HohenheimTools.build();
        declared = hand;
        AuthTools people = AuthTools.build();
        List<McpHostTool> described = new ArrayList<>(hand.tools());
        described.addAll(people.tools());
        McpHostServers.register(McpHostServer.builder(SERVER_NAME)
            .collections(McpRecordTools.byCode(), McpListTool.over(tools, AuthMcp.tools()), hand, people,
                McpDescribeTool.over(SERVER_NAME, described))
            .codeExamples(code(SiteModel.MODEL_ID, 14), code(InstanceModel.MODEL_ID, 33),
                code(DnsZoneModel.MODEL_ID, 7))
            .saysVersionOnce()
            // The opening words of the spec (scratch render.py HEAD); the version's sentence is saysVersionOnce's.
            .instructions(codes(),
                "A HOST is a machine apps run on.",
                "request is your own id for a call, a UUID: the same id again answers the first result instead of"
                    + " acting twice.",
                "A _set tool that takes code creates a record when code is left out and changes only what you send"
                    + " when it is given. A _set tool without code sets a thing named by its own key (a variable):"
                    + " the same key again changes it; each such tool says how its thing is taken away.",
                "read names each setting a _set tool takes by that tool's argument, its label in brackets, so a"
                    + " value read can be sent back as it is.",
                "Creating an app does not deploy it: call deploy and follow its run with "
                    + OperationRunOperations.READ.assistantName() + "; describe with a code says what you can do to"
                    + " that record now, each as the exact tool call.")
            .build());
    }

    /** @return the code the model's kind spells for a number, as the instructions give an example of */
    private static @NonNull String code(@NonNull Identifier model, long number) {
        return ((RecordCodeBehaviour.Allocated) Objects.requireNonNull(RecordCodeKinds.of(model))).spell(number);
    }

    /**
     * The list of each kind whose model declares no source of its own (a panel's derived one reads it, which no
     * programmatic caller reads), registered under its own id so no panel or picker changes: the operator's, or for an
     * app's variables everyone's who may view the app.
     *
     * AIDEV-NOTE: a model that declares its own source later is listed by that one (install prefers it); this one then
     * goes.
     *
     * @return per model, its registered list
     */
    private static @NonNull Map<Identifier, RecordSource<?>> ownLists() {
        Map<Identifier, RecordSource.Builder<?>> made = new LinkedHashMap<>();
        made.put(InstanceBackupModel.MODEL_ID, RecordSource.of(InstanceBackupModel.REF).project(
            InstanceBackupModel.STATUS, InstanceBackupModel.CREATED_AT, InstanceBackupModel.INSTANCE_ID));
        made.put(InstanceSnapshotModel.MODEL_ID, RecordSource.of(InstanceSnapshotModel.REF).project(
            InstanceSnapshotModel.NOTE, InstanceSnapshotModel.STATUS, InstanceSnapshotModel.CREATED_AT,
            InstanceSnapshotModel.INSTANCE_ID));
        made.put(DatabaseEngineModel.MODEL_ID, RecordSource.of(DatabaseEngineModel.REF)
            .search(DatabaseEngineModel.NAME));
        made.put(DatabaseDumpModel.MODEL_ID, RecordSource.of(DatabaseDumpModel.REF).project(DatabaseDumpModel.STATUS,
            DatabaseDumpModel.CREATED_AT, DatabaseDumpModel.DATABASE_ID));
        made.put(StackModel.MODEL_ID, RecordSource.of(StackModel.REF).search(StackModel.NAME));
        made.put(StackServiceModel.MODEL_ID, RecordSource.of(StackServiceModel.REF).project(StackServiceModel.NAME,
            StackServiceModel.IMAGE, StackServiceModel.ENABLED, StackServiceModel.STACK_ID)
            .search(StackServiceModel.NAME));
        made.put(EnvironmentModel.MODEL_ID, RecordSource.of(EnvironmentModel.REF).project(EnvironmentModel.NAME,
            EnvironmentModel.DESCRIPTION, EnvironmentModel.PROJECT_ID).search(EnvironmentModel.NAME));
        made.put(DnsZoneModel.MODEL_ID, RecordSource.of(DnsZoneModel.REF).search(DnsZoneModel.ORIGIN));
        made.put(DnsPeerModel.MODEL_ID, RecordSource.of(DnsPeerModel.REF).search(DnsPeerModel.NAME));
        made.put(GameDomainModel.MODEL_ID, RecordSource.of(GameDomainModel.REF).project(GameDomainModel.SITE_DOMAIN_ID,
            GameDomainModel.BACKEND_INSTANCE_ID, GameDomainModel.PROXY_INSTANCE_ID, GameDomainModel.BACKEND_PORT,
            GameDomainModel.ENABLED));
        // A config file's content is encrypted and never projected: a list shows its path and mode, by its owner.
        made.put(InstanceFileModel.MODEL_ID, RecordSource.of(InstanceFileModel.REF).project(
            InstanceFileModel.CONTAINER_PATH, InstanceFileModel.MODE, InstanceFileModel.INSTANCE_ID)
            .search(InstanceFileModel.CONTAINER_PATH));
        made.put(InstanceTemplateFileModel.MODEL_ID, RecordSource.of(InstanceTemplateFileModel.REF).project(
            InstanceTemplateFileModel.CONTAINER_PATH, InstanceTemplateFileModel.MODE,
            InstanceTemplateFileModel.TEMPLATE_ID).search(InstanceTemplateFileModel.CONTAINER_PATH));
        made.put(StackFileModel.MODEL_ID, RecordSource.of(StackFileModel.REF).project(StackFileModel.CONTAINER_PATH,
            StackFileModel.MODE, StackFileModel.STACK_SERVICE_ID).search(StackFileModel.CONTAINER_PATH));
        // A sign-in provider's config holds its secrets and is never projected: a list shows its name and kind.
        made.put(SiteAuthProviderModel.MODEL_ID, RecordSource.of(SiteAuthProviderModel.REF).project(
            SiteAuthProviderModel.NAME, SiteAuthProviderModel.PROVIDER_TYPE, SiteAuthProviderModel.REQUIRED_PERMISSION)
            .search(SiteAuthProviderModel.NAME));
        made.forEach((model, builder) -> builder.permission(HohenheimSources.ADMIN_ACCESS));
        // The /manage Provisioning tab's own scope: an app's own variables, for whoever may view the app. A secret's
        // value is never projected (a source refuses it); its kind says it is one.
        made.put(InstanceVariableModel.MODEL_ID, RecordSource.of(InstanceVariableModel.REF).project(
            InstanceVariableModel.KEY, InstanceVariableModel.KIND, InstanceVariableModel.PLAIN_VALUE,
            InstanceVariableModel.INSTANCE_ID)
            .search(InstanceVariableModel.KEY).scopedBy(TenantScopes.INSTANCE_VARIABLES));
        // The runs of an app's schedules, for whoever may view the app; its schedules are ManagePanel's own source.
        made.put(RecordScheduleRunModel.MODEL_ID, RecordSource.of(RecordScheduleRunModel.REF).project(
            RecordScheduleRunModel.STATUS, RecordScheduleRunModel.TRIGGER, RecordScheduleRunModel.ERROR,
            RecordScheduleRunModel.STARTED_AT, RecordScheduleRunModel.ENDED_AT, RecordScheduleRunModel.SCHEDULE_ID)
            .scopedBy(TenantScopes.INSTANCE_SCHEDULE_RUNS));
        Map<Identifier, RecordSource<?>> lists = new LinkedHashMap<>();
        for (Map.Entry<Identifier, RecordSource.Builder<?>> kind : made.entrySet()) {
            Identifier model = kind.getKey();
            RecordSource.Builder<?> builder = kind.getValue();
            if (RecordSourceRegistry.INSTANCE.declaredFor(model) != null) continue;
            Identifier id = HohenheimIds.id(model.getPath() + "_list");
            RecordSource<?> source = RecordSourceRegistry.INSTANCE.declaredFor(id);
            if (source == null) {
                source = builder.id(id).build();
                RecordSourceRegistry.INSTANCE.register(source);
            }
            lists.put(model, source);
        }
        return lists;
    }

    /**
     * @return the sentence naming every Hohenheim code by its letters and its kind, and a run's, read from the code
     *     kinds themselves
     */
    private static @NonNull String codes() {
        List<String> named = new ArrayList<>();
        List<Identifier> models = new ArrayList<>();
        for (Identifier model : RecordCodeKinds.models()) {
            if (HohenheimIds.PRODUCT.namespace().equals(model.getNamespace())) models.add(model);
        }
        models.add(OperationRunModel.MODEL_ID);
        for (Identifier model : models) {
            if (RecordCodeKinds.of(model) instanceof RecordCodeBehaviour.Allocated kind) {
                named.add(kind.prefix() + " " + AssistantNoun.of(model));
            }
        }
        return "Codes start with their kind's letters: " + String.join(", ", named) + ".";
    }
}
