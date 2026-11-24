package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.instance.InstanceAttachmentOperations;
import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.instance.InstanceLogOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceSnapshotOperations;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.instance.InstanceVariableOperations;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.game.GameDomainOperations;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SiteRedirectModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.preview.PreviewOperations;
import be.elevenways.hohenheim.server.HohenheimHostWiring;
import be.elevenways.hohenheim.server.cms.AccessRuleParts;
import be.elevenways.hohenheim.server.cms.BackupTargetParts;
import be.elevenways.hohenheim.server.cms.BuildHookParts;
import be.elevenways.hohenheim.server.cms.CertificateOperations;
import be.elevenways.hohenheim.server.cms.DatabaseDumpParts;
import be.elevenways.hohenheim.server.cms.DatabaseParts;
import be.elevenways.hohenheim.server.cms.DnsOperations;
import be.elevenways.hohenheim.server.cms.DomainParts;
import be.elevenways.hohenheim.server.cms.NotificationChannelParts;
import be.elevenways.hohenheim.server.cms.ProjectParts;
import be.elevenways.hohenheim.server.cms.ReconcileFindingParts;
import be.elevenways.hohenheim.server.cms.ReleasedClaimParts;
import be.elevenways.hohenheim.server.cms.RuntimeImageParts;
import be.elevenways.hohenheim.server.cms.ServerParts;
import be.elevenways.hohenheim.server.cms.SiteWrites;
import be.elevenways.hohenheim.server.cms.SpamserviceOperations;
import be.elevenways.hohenheim.server.cms.StackOperations;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.source.GitProviderOperations;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpListTool;
import be.elevenways.zenit.ai.server.mcp.host.McpRecordTools;
import be.elevenways.zenit.ai.server.mcp.host.McpToolSet;
import be.elevenways.zenit.auth.server.mcp.AuthMcp;
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
import be.elevenways.zenit.server.operation.OperationRunOperations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
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

    /** The models whose sources the server lists, in the order its tools are offered. */
    private static final List<Identifier> LISTED = List.of(SiteModel.MODEL_ID, SiteDomainModel.MODEL_ID,
        ProtectedPathModel.MODEL_ID, SiteRedirectModel.MODEL_ID, AccessListModel.MODEL_ID, AccessRuleModel.MODEL_ID,
        CertificateModel.MODEL_ID, InstanceModel.MODEL_ID, InstanceTemplateModel.MODEL_ID,
        InstanceDeviceModel.MODEL_ID, InstanceVolumeModel.MODEL_ID, InstanceDatabaseModel.MODEL_ID,
        PreviewDeploymentModel.MODEL_ID, ProjectModel.MODEL_ID, DatabaseModel.MODEL_ID, GitProviderModel.MODEL_ID,
        ServerModel.MODEL_ID, RuntimeImageModel.MODEL_ID, BackupTargetModel.MODEL_ID, SystemUserModel.MODEL_ID);

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

    /** @return Hohenheim's own tools, null before {@link #install()}: what its coverage is judged against */
    public static @Nullable McpToolSet tools() {
        return tools;
    }

    /** Registers the server once per process; a second call changes nothing. */
    public static synchronized void install() {
        if (McpHostServers.get(SERVER_NAME) != null) return;
        List<RecordSource<?>> sources = new ArrayList<>();
        for (Identifier model : LISTED) {
            // A source a role leaves out (backup targets without the instance role) is no tool there.
            RecordSource<?> source = RecordSourceRegistry.INSTANCE.declaredFor(model);
            if (source != null) sources.add(source);
        }
        List<Operation<?, ?, ?>> placed = new ArrayList<>(List.of(
            InstanceOperations.DEPLOY, InstanceOperations.ROLLBACK,
            InstanceOperations.BACKUP, InstanceOperations.CONNECT_WEBHOOK, InstanceOperations.SNAPSHOT,
            InstanceOperations.INSTALL, InstanceOperations.REINSTALL, InstanceOperations.CAPTURE_TEMPLATE,
            InstanceOperations.DESTROY_WITH_DATA, InstanceOperations.DELETE, InstanceOperations.ATTACH_INSTALL_MEDIA,
            InstanceOperations.DETACH_INSTALL_MEDIA, InstanceBackupOperations.DELETE_BACKUP,
            InstanceSnapshotOperations.RESTORE,
            InstanceSnapshotOperations.DELETE, InstanceAttachmentOperations.DETACH_DEVICE,
            InstanceAttachmentOperations.DELETE_DATABASE_LINK, InstanceTemplateOperations.CREATE_INSTANCE_FROM_TEMPLATE,
            InstanceTemplateOperations.APPROVE_TEMPLATE, InstanceTemplateOperations.UNAPPROVE_TEMPLATE,
            VolumeOperations.DESTROY, GameDomainOperations.DELETE,
            SiteOperations.ENABLE, SiteOperations.DISABLE, SiteOperations.STOP_FORCING_HTTPS, SiteOperations.CLONE,
            SiteWrites.MANAGE_UPDATE, SiteWrites.DELETE, DomainParts.CHECK_REACH,
            CertificateOperations.REQUEST, CertificateOperations.REQUEST_FOR_DOMAIN, CertificateOperations.REISSUE,
            CertificateOperations.CONTINUE_DNS, CertificateOperations.RENEW, AccessRuleParts.TOGGLE,
            AccessRuleParts.DELETE, BuildHookParts.REVOKE, DnsOperations.DELETE_ZONE, DnsOperations.DELETE_PEER,
            DatabaseParts.DELETE, DatabaseParts.MOVE_TO_SHARED, DatabaseParts.FINISH_OLD_ENGINE,
            DatabaseParts.BACK_UP_NOW, DatabaseParts.FORCE_DELETE, DatabaseParts.DELETE_ENGINE,
            DatabaseParts.FORCE_DELETE_ENGINE,
            DatabaseDumpParts.DELETE, StackOperations.DEPLOY, StackOperations.STOP, StackOperations.ROLLBACK,
            StackOperations.PURGE_VOLUMES, StackOperations.RECLAIM_IMAGES, StackOperations.DELETE_STACK,
            StackOperations.DELETE_SERVICE,
            ServerParts.DELETE, RuntimeImageParts.DELETE, ProjectParts.DELETE, ReconcileFindingParts.REMOVE,
            ReleasedClaimParts.LIFT, SpamserviceOperations.START, SpamserviceOperations.STOP,
            SpamserviceOperations.RESTART));
        McpToolSet.Builder set = McpToolSet.builder()
            .lists(sources.toArray(new RecordSource<?>[0]))
            .places(placed.toArray(new Operation<?, ?, ?>[0]))
            .answersWith(InstanceOperations.START, HohenheimMcpAnswers::power)
            .answersWith(InstanceOperations.STOP, HohenheimMcpAnswers::power)
            .answersWith(InstanceOperations.RESTART, HohenheimMcpAnswers::power)
            .answersWith(InstanceOperations.CONSOLE_COMMAND, HohenheimMcpAnswers::output)
            .answersWith(InstanceOperations.EXEC, HohenheimMcpAnswers::exec)
            .answersWith(InstanceOperations.APP_UPDATE, HohenheimMcpAnswers::output)
            .answersWith(PreviewOperations.EXPIRE, HohenheimMcpAnswers::output)
            .answersWith(StackOperations.REFRESH, HohenheimMcpAnswers::output)
            .answersWith(SpamserviceOperations.TEST, HohenheimMcpAnswers::output)
            .answersWith(GitProviderOperations.TEST_CONNECTION, HohenheimMcpAnswers::connection)
            .answersWith(BackupTargetParts.TEST, HohenheimMcpAnswers::tested)
            .answersWith(NotificationChannelParts.TEST, HohenheimMcpAnswers::delivered)
            .answersWith(DnsOperations.CHECK_HEALTH, HohenheimMcpAnswers::told)
            .answersWith(DnsOperations.NEGOTIATE_KEY, HohenheimMcpAnswers::told)
            .answersWith(InstanceLogOperations.READ_LOG, HohenheimMcpAnswers::log)
            .answersWith(InstanceLogOperations.LIST_DEPLOYS, HohenheimMcpAnswers::deploys)
            .answersWith(InstanceLogOperations.READ_BUILD_LOG, HohenheimMcpAnswers::log)
            .answersWith(InstanceLogOperations.READ_DEPLOY_LOG, HohenheimMcpAnswers::log)
            .answersWith(InstanceVariableOperations.LIST_VARIABLES, HohenheimMcpAnswers::variables)
            .answersWith(InstanceVariableOperations.SET_VARIABLE, HohenheimMcpAnswers::variableWritten)
            .answersWith(InstanceVariableOperations.UNSET_VARIABLE, HohenheimMcpAnswers::variableWritten)
            .answersWith(InstanceVariableOperations.GENERATE_SECRET, HohenheimMcpAnswers::variableWritten);
        for (var operation : ServerParts.lifecycleForAssistants()) {
            set.answersWith(operation, HohenheimMcpAnswers::said);
        }
        tools = set.build();
        McpHostServers.register(McpHostServer.builder(SERVER_NAME)
            .collections(McpRecordTools.shared(), McpListTool.over(tools, AuthMcp.tools()))
            .codeExamples(code(SiteModel.MODEL_ID, 14), code(InstanceModel.MODEL_ID, 33),
                code(DnsZoneModel.MODEL_ID, 7))
            .saysVersionOnce()
            .instructions(codes(),
                "list with a kind lists its records by their codes; read reads records in full by their codes;"
                    + " history tells what happened to one.",
                "Deploy an app with " + InstanceOperations.DEPLOY.assistantName() + " and follow its run with "
                    + OperationRunOperations.READ.assistantName() + "; when a step failed, "
                    + InstanceLogOperations.LIST_DEPLOYS.assistantName() + " names the build and the deploy, and "
                    + InstanceLogOperations.READ_BUILD_LOG.assistantName() + " and "
                    + InstanceLogOperations.READ_DEPLOY_LOG.assistantName() + " read their logs.")
            .build());
    }

    /** @return the code the model's kind spells for a number, as the instructions give an example of */
    private static @NonNull String code(@NonNull Identifier model, long number) {
        return ((RecordCodeBehaviour.Allocated) Objects.requireNonNull(RecordCodeKinds.of(model))).spell(number);
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
