package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.host.HostPostureAcknowledgement;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Set;

/**
 * The creates and changes of hosts, projects, environments, stacks and stack services as operations, each through its
 * panel resource ({@link PanelRecordWrites}).
 *
 * AIDEV-NOTE: a host's risk acceptance is the Accept risk button's own write (HostPostureAcknowledgement), with its
 * refusals; the name typed is checked here, where the panel's typed confirmation checks it in the browser.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HostingRecordWrites {

    /** The argument naming the host a stack deploys to. */
    public static final String HOST = "host";

    /** The argument naming the stack a new service belongs to. */
    public static final String STACK = "stack";

    /** The argument naming the project an environment belongs to. */
    public static final String PROJECT = "project";

    /** The argument accepting a shared-container host's risk by typing its name. */
    public static final String ACCEPT_POSTURE = "accept_posture";

    private static final StringField ACCEPT = StringField.builder(ACCEPT_POSTURE)
        .help(HohenheimMicrocopy.HELP.of("accept_posture")).build();

    /** A host's create and change, its risk acceptance a change's own step. */
    public static final PanelRecordWrites HOSTS = PanelRecordWrites.of("host", HostModel.MODEL_ID,
            SubjectType.record(HostModel.REF), HostModel.ID, HohenheimSlugs.HOSTS)
        .settings(List.of(HostModel.NAME, HostModel.RUNTIME, HostModel.SSH_TARGET, HostModel.INCUS_URL,
            HostParts.INCUS_TRUST_TOKEN, HostModel.POSTURE, HostModel.PUBLIC_IPV4, HostModel.PUBLIC_IPV6,
            HostModel.LAN_PARENT))
        .also(List.of(Plain.of(ACCEPT)), HostingRecordWrites::acceptance)
        // Enrolling spends a trust token on the daemon, outside the receipt's transaction, as the panel's save does.
        .createsOutsideTransaction().changesAs(CmsCommands.EXTERNAL)
        .build();

    /** A project's create and change. */
    public static final PanelRecordWrites PROJECTS = PanelRecordWrites.of("project", ProjectModel.MODEL_ID,
            SubjectType.record(ProjectModel.REF), ProjectModel.ID, HohenheimSlugs.PROJECTS)
        .settings(List.of(ProjectModel.NAME, ProjectModel.DESCRIPTION))
        .build();

    /** An environment's create and change; its project moves only while nothing uses it (ProjectGuards). */
    public static final PanelRecordWrites ENVIRONMENTS = PanelRecordWrites.of("environment",
            EnvironmentModel.MODEL_ID, SubjectType.record(EnvironmentModel.REF), EnvironmentModel.ID,
            HohenheimSlugs.ENVIRONMENTS)
        .link(PROJECT, HohenheimMicrocopy.HELP.of("environment_project_code"), EnvironmentModel.PROJECT_ID,
            ProjectModel.MODEL_ID, false)
        .settings(List.of(EnvironmentModel.NAME, EnvironmentModel.DESCRIPTION))
        .build();

    /** A stack's create and change; a rename or a new host is refused while it has containers (StackParts). */
    public static final PanelRecordWrites STACKS = PanelRecordWrites.of("stack", StackModel.MODEL_ID,
            StackOperations.STACK, StackModel.ID, HohenheimSlugs.STACKS)
        .settings(List.of(StackModel.NAME, StackModel.ENABLED))
        .link(HOST, HohenheimMicrocopy.HELP.of("stack_host_code"), StackModel.HOST_ID, HostModel.MODEL_ID, false)
        .settings(List.of(StackModel.REGISTRY_SERVER, StackModel.REGISTRY_USER, StackModel.REGISTRY_PASSWORD,
            StackModel.DESCRIPTION))
        // The rename and move gates ask the stack's worker how many containers it owns, outside the transaction.
        .changesAs(CmsCommands.EXTERNAL)
        .build();

    /** A stack service's create and change; it reaches its container at the stack's next deploy. */
    public static final PanelRecordWrites SERVICES = PanelRecordWrites.of("stack_service",
            StackServiceModel.MODEL_ID, StackOperations.SERVICE, StackServiceModel.ID, HohenheimSlugs.STACK_SERVICES)
        .link(STACK, HohenheimMicrocopy.HELP.of("stack_service_stack_code"), StackServiceModel.STACK_ID,
            StackModel.MODEL_ID, true)
        .settings(List.of(StackServiceModel.NAME, StackServiceModel.ENABLED, StackServiceModel.IMAGE,
            StackServiceModel.COMMAND, StackServiceModel.ENVIRONMENT, StackServiceModel.CAPABILITIES,
            StackServiceModel.MOUNTS, StackServiceModel.PORTS, StackServiceModel.DEPENDS_ON,
            StackServiceModel.HEALTH_CMD, StackServiceModel.HEALTH_INTERVAL_SECONDS,
            StackServiceModel.HEALTH_TIMEOUT_SECONDS, StackServiceModel.HEALTH_RETRIES,
            StackServiceModel.HEALTH_START_PERIOD_SECONDS, StackServiceModel.RESTART_POLICY,
            StackServiceModel.MEMORY_LIMIT_MB, StackServiceModel.CPU_LIMIT))
        .build();

    private HostingRecordWrites() {
    }

    /**
     * Records a risk acceptance as the Accept risk button does, once the name typed equals the host's: its own
     * refusals (no acceptance needed, one already current, no person to record) speak for it, naming the argument.
     *
     * AIDEV-NOTE: it runs after the call's other settings were written, on the host as they left it, so a call whose
     * other settings are refused accepts nothing; the gate is the host change's own (an operator's panel).
     *
     * @return the acceptance, written after the record's own settings
     * @throws Violations naming {@link #ACCEPT_POSTURE} when it is not the host's name exactly
     */
    private static @NonNull Runnable acceptance(@NonNull Row host, OperationInput.@NonNull Values input,
                                                @NonNull Set<String> sent, @NonNull AccessContext caller) {
        Object typed = input.get(ACCEPT_POSTURE);
        if (typed == null) return () -> { };
        String name = host.get(HostModel.NAME);
        if (!String.valueOf(typed).equals(name)) {
            throw Violations.ofField(ACCEPT_POSTURE, typed, HohenheimMicrocopy.VIOLATIONS.of("accept_posture_name")
                .withArg("name", String.valueOf(name)));
        }
        Object key = host.get(HostModel.ID);
        return () -> {
            try {
                HostPostureAcknowledgement.record(Models.get(HostModel.class).findById(key));
            } catch (Violations refused) {
                // The button's refusal is the form's; here it names the argument that asked for it.
                throw Violations.ofField(ACCEPT_POSTURE, typed, refused.all().getFirst().message());
            }
        };
    }
}
