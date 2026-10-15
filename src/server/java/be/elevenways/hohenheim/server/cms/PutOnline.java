package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.server.instance.InstallMedia;
import be.elevenways.hohenheim.ports.PortPurpose;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.tls.HostnameReach;
import be.elevenways.hohenheim.server.upstream.kinds.StreamUpstreamKind;
import be.elevenways.hohenheim.upstream.UpstreamKindInfo;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.FieldFormEntryDefaults;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.FormStep;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.StepAnswers;
import be.elevenways.zenit.common.edit.StepSummary;
import be.elevenways.zenit.common.edit.SummaryLine;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationSteps;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.forms.common.edit.ItemList;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * "Put something online": one flow that creates an app, its address and its certificate, as background operations
 * behind one wizard page ({@link PutOnlinePage}).
 *
 * AIDEV-NOTE: six operations because the input differs by WHAT is put online. A template's form is that template's
 * own variables, resolved per selected template (the subject), so {@link #PUT_ONLINE} takes the template as its
 * subject, exactly as the create-from-template lane does; a template whose workload is reached on its ports
 * ({@code PutOnlineReach.PORTS}, a game server) is {@link #PUT_SERVICE_ONLINE} instead, with a Ports step and an
 * optional game address where the others ask an address and HTTPS. An address that needs no workload (a redirect, an
 * existing service, static files, TLS passthrough) has no subject; {@link #PUT_ADDRESS_ONLINE} asks the site form's upstream
 * kind and settings. A workload of a kind that serves a website (a Git app, a container image) has no subject either;
 * {@link #PUT_WORKLOAD_ONLINE} asks that kind's own settings, the instance form's. The same kind reached on ports it
 * publishes (a container image of a game server) is {@link #PUT_WORKLOAD_SERVICE_ONLINE}: its settings, then the
 * ports themselves as rows and the game address. A machine of a kind that serves no website (a virtual machine, a
 * Linux container) is {@link #PUT_MACHINE_ONLINE}: the same kind's own settings, without the address and HTTPS steps.
 * The workload ones end in the same Website and Certificate steps (PutOnlineHandlers), so the going-live half is one
 * implementation, and every run starts what it made the same way. All run in the background: their run page is the
 * "Going live" step.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class PutOnline {

    /** The HTTPS choice: a Let's Encrypt certificate ordered once the address exists. */
    public static final String HTTPS_AUTOMATIC = HttpsChoice.AUTOMATIC.token();

    /** The HTTPS choice: no order now; the address answers over HTTP until a certificate is added. */
    public static final String HTTPS_LATER = HttpsChoice.LATER.token();

    /**
     * The address visitors type. Named like the site create's first hostname, so route refusals path on it. Only the
     * website flows ask it, so its help is the website reach's.
     */
    public static final StringField HOSTNAME = StringField.builder(SiteDomainModel.HOSTNAME.getName())
        .label(HohenheimMicrocopy.PUT_ONLINE.of("address"))
        .help(Objects.requireNonNull(PutOnlineReach.WEBSITE.addressHelp(), "a website asks an address"))
        .placeholder("shop.example.com")
        .build();

    /** The same address for the address kinds, which have nothing to serve without one (so no "leave it empty"). */
    public static final StringField ADDRESS_HOSTNAME = StringField.builder(SiteDomainModel.HOSTNAME.getName())
        .label(HohenheimMicrocopy.PUT_ONLINE.of("address"))
        .help(HohenheimMicrocopy.PUT_ONLINE.of("address_help_required"))
        .placeholder("shop.example.com")
        .build();

    /** The certificate card the step opens on: Automatic, the choice that needs nothing more from the operator. */
    public static final StringField HTTPS = StringField.builder("https")
        .label(HohenheimMicrocopy.PUT_ONLINE.of("certificate")).defaultValue(HTTPS_AUTOMATIC).build();

    /**
     * The access list the new website follows, from the lists Hohenheim already has (their IP/CIDR rules and HTTP
     * basic auth users live on the list); empty for an address everyone reaches.
     */
    static final RelationPick ACCESS_LIST = accessListPick(SiteModel.ACCESS_LIST_ID);

    /**
     * A stream's access list: the same site column, its help saying what a raw port can apply (address rules only,
     * as {@code SitePortClaims.credentialRefusal} enforces).
     */
    static final RelationPick STREAM_ACCESS_LIST = accessListPick(IntegerField.builder()
        .name(SiteModel.ACCESS_LIST_ID.getName()).label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("access_list"))
        .help(HohenheimMicrocopy.HELP.of("stream_access_list")).build());

    private static @NonNull RelationPick accessListPick(@NonNull IntegerField field) {
        return RelationPick.of(field, AccessListModel.MODEL_ID)
            .creatable(false)
            .emptyReason(context -> HohenheimMicrocopy.PUT_ONLINE.of("access_list_none"))
            .build();
    }

    /** The host ports a service's declared ports are published on, one entry per declared port (per template). */
    public static final String PORTS = "ports";

    /** The optional name players type to reach a game server; an operator's to give, like an address. */
    public static final StringField GAME_ADDRESS = StringField.builder(InstanceModel.GAME_ADDRESS.getName())
        .label(HohenheimMicrocopy.PUT_ONLINE.of("game_address"))
        .help(HohenheimMicrocopy.PUT_ONLINE.of("game_address_help"))
        .placeholder("mc.example.com")
        .build();

    /* The journey's step names, one home: the chooser, the wizard's own steps and the run that ends it. */
    static final Microcopy STEP_WHAT = HohenheimMicrocopy.PUT_ONLINE.of("step_what");
    static final Microcopy STEP_WHERE = HohenheimMicrocopy.PUT_ONLINE.of("step_where");
    static final Microcopy STEP_OPTIONS = HohenheimMicrocopy.PUT_ONLINE.of("step_options");
    static final Microcopy STEP_HTTPS = HohenheimMicrocopy.PUT_ONLINE.of("step_https");
    static final Microcopy STEP_LIVE = HohenheimMicrocopy.PUT_ONLINE.of("step_live");
    static final Microcopy STEP_PORTS = HohenheimMicrocopy.PUT_ONLINE.of("step_ports");

    /* The journey's step before every flow's own form (the chooser) and its last, the run page: each operation's run
       walks What, then its own form's steps as the wizard showed them, then Going live. */
    static final List<Microcopy> BEFORE_FORM = List.of(STEP_WHAT);

    /** The run's steps for a template: the app, its install, its website, its certificate, then live. */
    private static final OperationSteps APP_STEPS = OperationSteps.of(
        OperationSteps.step("app", HohenheimMicrocopy.PUT_ONLINE.of("run_app")),
        OperationSteps.step("install", HohenheimMicrocopy.PUT_ONLINE.of("run_install")),
        OperationSteps.step("website", HohenheimMicrocopy.PUT_ONLINE.of("run_website")),
        OperationSteps.step("certificate", HohenheimMicrocopy.PUT_ONLINE.of("run_certificate")),
        OperationSteps.step("live", HohenheimMicrocopy.PUT_ONLINE.of("run_live")))
        .inJourneyAfterItsForm(BEFORE_FORM, STEP_LIVE)
        .titled(titleFrom(InstanceTemplateOperations.NAME.getName()));

    /** The run's steps for a service from a template: the app, its install, its ports, its game address, then live. */
    private static final OperationSteps SERVICE_STEPS = OperationSteps.of(
        OperationSteps.step("app", HohenheimMicrocopy.PUT_ONLINE.of("run_app")),
        OperationSteps.step("install", HohenheimMicrocopy.PUT_ONLINE.of("run_install")),
        OperationSteps.step("ports", HohenheimMicrocopy.PUT_ONLINE.of("run_ports")),
        OperationSteps.step("game_address", HohenheimMicrocopy.PUT_ONLINE.of("run_game_address")),
        OperationSteps.step("live", HohenheimMicrocopy.PUT_ONLINE.of("run_live")))
        .inJourneyAfterItsForm(BEFORE_FORM, STEP_LIVE)
        .titled(titleFrom(InstanceTemplateOperations.NAME.getName()));

    /** The run's steps for an address: its website and its certificate. */
    private static final OperationSteps ADDRESS_STEPS = OperationSteps.of(
        OperationSteps.step("website", HohenheimMicrocopy.PUT_ONLINE.of("run_website")),
        OperationSteps.step("certificate", HohenheimMicrocopy.PUT_ONLINE.of("run_certificate")))
        .inJourneyAfterItsForm(BEFORE_FORM, STEP_LIVE)
        .titled(titleFrom(SiteModel.NAME.getName()));

    /** The run's steps for a TCP/UDP stream: its public port, then its forward. */
    private static final OperationSteps STREAM_STEPS = OperationSteps.of(
        OperationSteps.step("port", HohenheimMicrocopy.PUT_ONLINE.of("run_stream_port")),
        OperationSteps.step("forward", HohenheimMicrocopy.PUT_ONLINE.of("run_stream_forward")))
        .inJourneyAfterItsForm(BEFORE_FORM, STEP_LIVE)
        .titled(titleFrom(SiteModel.NAME.getName()));

    /** The run's steps for a workload of one kind: the app, its website, its certificate, then live. */
    private static final OperationSteps WORKLOAD_STEPS = OperationSteps.of(
        OperationSteps.step("app", HohenheimMicrocopy.PUT_ONLINE.of("run_app")),
        OperationSteps.step("website", HohenheimMicrocopy.PUT_ONLINE.of("run_website")),
        OperationSteps.step("certificate", HohenheimMicrocopy.PUT_ONLINE.of("run_certificate")),
        OperationSteps.step("live", HohenheimMicrocopy.PUT_ONLINE.of("run_live")))
        .inJourneyAfterItsForm(BEFORE_FORM, STEP_LIVE)
        .titled(titleFrom(InstanceTemplateOperations.NAME.getName()));

    /** The run's steps for a machine: the machine, in its group's words, then its first start. */
    private static final OperationSteps MACHINE_STEPS = OperationSteps.of(
        OperationSteps.step("app", PutOnlineGroup.MACHINE.words(HohenheimMicrocopy.PUT_ONLINE.of("run_app"))),
        OperationSteps.step("live", HohenheimMicrocopy.PUT_ONLINE.of("run_live")))
        .inJourneyAfterItsForm(BEFORE_FORM, STEP_LIVE)
        .titled(titleFrom(InstanceTemplateOperations.NAME.getName()));

    /** The run's steps for a service of one kind: the app, its ports, its game address, then live. */
    private static final OperationSteps WORKLOAD_SERVICE_STEPS = OperationSteps.of(
        OperationSteps.step("app", HohenheimMicrocopy.PUT_ONLINE.of("run_app")),
        OperationSteps.step("ports", HohenheimMicrocopy.PUT_ONLINE.of("run_ports")),
        OperationSteps.step("game_address", HohenheimMicrocopy.PUT_ONLINE.of("run_game_address")),
        OperationSteps.step("live", HohenheimMicrocopy.PUT_ONLINE.of("run_live")))
        .inJourneyAfterItsForm(BEFORE_FORM, STEP_LIVE)
        .titled(titleFrom(InstanceTemplateOperations.NAME.getName()));

    /** What one port of a kind-led service is for; Other names it with {@link #PORT_NAME}. */
    public static final StringField PORT_PURPOSE = StringField.builder("purpose")
        .label(HohenheimMicrocopy.PUT_ONLINE.of("service_port_purpose"))
        .defaultValue(PortPurpose.GAME.token())
        .build();

    /** The name of a port whose purpose is Other: its key in the published ports. */
    public static final StringField PORT_NAME = StringField.builder("name")
        .label(HohenheimMicrocopy.PUT_ONLINE.of("service_port_name"))
        .help(HohenheimMicrocopy.PUT_ONLINE.of("service_port_name_help"))
        .build();

    public static final IntegerField PORT_CONTAINER = IntegerField.builder().name("container_port")
        .label(HohenheimMicrocopy.PUT_ONLINE.of("service_port_container"))
        .required()
        .build();

    public static final StringField PORT_PROTOCOL = StringField.builder("protocol")
        .label(HohenheimMicrocopy.PUT_ONLINE.of("service_port_protocol"))
        .defaultValue(PublishedPort.TCP)
        .build();

    /** The host port it is published on; empty for one the ledger picks when the run claims it. */
    public static final IntegerField PORT_HOST = IntegerField.builder().name("host_port")
        .label(HohenheimMicrocopy.PUT_ONLINE.of("service_port_host"))
        .help(HohenheimMicrocopy.PUT_ONLINE.of("service_port_host_help"))
        .build();

    /**
     * The ports a kind-led service publishes, one row each, named as the template flow's host ports are
     * ({@link #PORTS}): the same step, a different shape, since nothing declared them before.
     */
    public static final ItemList SERVICE_PORTS = ItemList.of(PORTS, FormSpec.builder()
        .add(Select.of(PORT_PURPOSE).options(OptionSource.of(purposeOptions())).clearable(false).build())
        .add(PORT_NAME)
        .add(PORT_CONTAINER)
        .add(Select.of(PORT_PROTOCOL)
            .options(OptionSource.of(
                FieldOption.of(PublishedPort.TCP, HohenheimMicrocopy.PUT_ONLINE.of("service_port_tcp")),
                FieldOption.of(PublishedPort.UDP, HohenheimMicrocopy.PUT_ONLINE.of("service_port_udp"))))
            .clearable(false)
            .build())
        .add(PORT_HOST)
        .build());

    /** The host pick and the owner a template's app is charged to: Online-2's "Runs on" card. */
    private static final FormSection RUNS_ON = FormSection.of("runs_on",
            HohenheimMicrocopy.PUT_ONLINE.of("runs_on_card"),
            InstanceTemplateOperations.SERVER_ID.getName(), InstanceTemplateOperations.PROJECT_ID.getName(),
            InstanceTemplateOperations.ENVIRONMENT_ID.getName())
        .describe(HohenheimMicrocopy.PUT_ONLINE.of("runs_on_card_lead"));

    /** The kind-led flows' "Runs on" card: the host pick alone, since they charge the operator's own quota. */
    private static final FormSection RUNS_ON_HOST = FormSection.of("runs_on",
            HohenheimMicrocopy.PUT_ONLINE.of("runs_on_card"), InstanceTemplateOperations.SERVER_ID.getName())
        .describe(HohenheimMicrocopy.PUT_ONLINE.of("runs_on_card_host_lead"));

    private static final FormSpec TEMPLATE_INPUT = FormSpec.builder()
        .add(InstanceTemplateOperations.NAME)
        .add(HOSTNAME)
        .add(InstanceTemplateOperations.SERVER_ID)
        .add(InstanceTemplateOperations.PROJECT_ID)
        .add(InstanceTemplateOperations.ENVIRONMENT_ID)
        .add(Nested.of(InstanceTemplateOperations.VARIABLES).subSpec(FormSpec.builder().build()).build())
        .add(httpsChoice())
        .add(CertificateModel.CERTIFICATE_PEM)
        .add(CertificateModel.PRIVATE_KEY_PEM)
        .add(ACCESS_LIST)
        .showWhen(CertificateModel.CERTIFICATE_PEM.getName(), HTTPS.getName(), uploadTokens())
        .showWhen(CertificateModel.PRIVATE_KEY_PEM.getName(), HTTPS.getName(), uploadTokens())
        .section(RUNS_ON)
        .step(FormStep.of("where", STEP_WHERE, InstanceTemplateOperations.NAME.getName(), HOSTNAME.getName(),
            InstanceTemplateOperations.SERVER_ID.getName(), InstanceTemplateOperations.PROJECT_ID.getName(),
            InstanceTemplateOperations.ENVIRONMENT_ID.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_where_lead")))
        .step(new FormStep(InstanceTemplateOperations.VARIABLES, STEP_OPTIONS,
            HohenheimMicrocopy.PUT_ONLINE.of("step_options_lead"),
            List.of(InstanceTemplateOperations.VARIABLES)))
        .step(httpsStep(PutOnline::templateSummary))
        .build();

    /**
     * A service template's input: where it runs and the template's variables as for a website, then its Ports step
     * instead of an address and HTTPS. The ports entry is the template's own (PutOnlineHandlers.serviceScope).
     */
    private static final FormSpec SERVICE_INPUT = FormSpec.builder()
        .add(InstanceTemplateOperations.NAME)
        .add(InstanceTemplateOperations.SERVER_ID)
        .add(InstanceTemplateOperations.PROJECT_ID)
        .add(InstanceTemplateOperations.ENVIRONMENT_ID)
        .add(Nested.of(InstanceTemplateOperations.VARIABLES).subSpec(FormSpec.builder().build()).build())
        .add(Nested.of(PORTS).subSpec(FormSpec.builder().build()).build())
        .add(GAME_ADDRESS)
        .section(RUNS_ON)
        .step(FormStep.of("where", STEP_WHERE, InstanceTemplateOperations.NAME.getName(),
            InstanceTemplateOperations.SERVER_ID.getName(), InstanceTemplateOperations.PROJECT_ID.getName(),
            InstanceTemplateOperations.ENVIRONMENT_ID.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_service_where_lead")))
        .step(new FormStep(InstanceTemplateOperations.VARIABLES, STEP_OPTIONS,
            HohenheimMicrocopy.PUT_ONLINE.of("step_options_lead"),
            List.of(InstanceTemplateOperations.VARIABLES)))
        .step(FormStep.of(PORTS, STEP_PORTS, PORTS, GAME_ADDRESS.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_ports_lead"))
            .summarizedBy(PutOnline::serviceSummary))
        .build();

    private static final FormSpec ADDRESS_INPUT = FormSpec.builder()
        .add(SiteModel.NAME)
        .add(ADDRESS_HOSTNAME)
        .add(Select.of(SiteModel.UPSTREAM_KIND)
            .options(OptionSource.dynamic(context -> FieldFormEntryDefaults.enumOptionSource(SiteModel.UPSTREAM_KIND)
                .resolve(context).stream().filter(option -> offeredAsApp(option.value())
                    && addressReachOf(option.value()) == PutOnlineReach.WEBSITE).toList()))
            .presentation(Select.Presentation.CARDS)
            .clearable(false)
            .build())
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SETTINGS))
        .add(httpsChoice())
        .add(CertificateModel.CERTIFICATE_PEM)
        .add(CertificateModel.PRIVATE_KEY_PEM)
        .add(ACCESS_LIST)
        .showWhen(CertificateModel.CERTIFICATE_PEM.getName(), HTTPS.getName(), uploadTokens())
        .showWhen(CertificateModel.PRIVATE_KEY_PEM.getName(), HTTPS.getName(), uploadTokens())
        // Served by Hohenheim's own proxy: no host is asked and no "Runs on" card is drawn, so its lead never says
        // where it runs (the upstream kind's requiresInstance fact is what routes a kind to this flow).
        .step(FormStep.of("where", STEP_WHERE, SiteModel.NAME.getName(), ADDRESS_HOSTNAME.getName(),
            SiteModel.UPSTREAM_KIND.getName(), SiteModel.SETTINGS.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_address_where_lead")))
        .step(httpsStep(PutOnline::addressSummary))
        .build();

    /**
     * A TCP/UDP stream (npmplus's streams): what it forwards to, then its Ports step: the public port on the proxy's
     * host, its protocol and the access list. No address and no HTTPS: it is reached on its port, and nothing runs on
     * a host for it.
     */
    private static final FormSpec STREAM_INPUT = FormSpec.builder()
        .add(SiteModel.NAME)
        .add(StreamUpstreamKind.FORWARD_HOST)
        .add(StreamUpstreamKind.FORWARD_PORT)
        .add(StreamUpstreamKind.LISTEN_PORT)
        .add(StreamUpstreamKind.PROTOCOL)
        .add(STREAM_ACCESS_LIST)
        .step(FormStep.of("where", STEP_WHERE, SiteModel.NAME.getName(), StreamUpstreamKind.FORWARD_HOST.getName(),
            StreamUpstreamKind.FORWARD_PORT.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_stream_where_lead")))
        .step(FormStep.of(PORTS, STEP_PORTS, StreamUpstreamKind.LISTEN_PORT.getName(),
            StreamUpstreamKind.PROTOCOL.getName(), SiteModel.ACCESS_LIST_ID.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_stream_ports_lead")))
        .build();

    private static final FormSpec WORKLOAD_INPUT = FormSpec.builder()
        .add(InstanceTemplateOperations.NAME)
        .add(HOSTNAME)
        .add(declaredHostPick())
        .add(InstanceModel.KIND)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.SETTINGS))
        .add(httpsChoice())
        .add(CertificateModel.CERTIFICATE_PEM)
        .add(CertificateModel.PRIVATE_KEY_PEM)
        .add(ACCESS_LIST)
        .showWhen(CertificateModel.CERTIFICATE_PEM.getName(), HTTPS.getName(), uploadTokens())
        .showWhen(CertificateModel.PRIVATE_KEY_PEM.getName(), HTTPS.getName(), uploadTokens())
        .section(RUNS_ON_HOST)
        .step(FormStep.of("where", STEP_WHERE, InstanceTemplateOperations.NAME.getName(), HOSTNAME.getName(),
            InstanceTemplateOperations.SERVER_ID.getName(), InstanceModel.KIND.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_where_lead")))
        .step(FormStep.of("settings", STEP_OPTIONS, InstanceModel.SETTINGS.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_settings_lead")))
        .step(httpsStep(PutOnline::workloadSummary))
        .build();

    /**
     * A machine's input: where it runs, then its kind's own settings and, for an empty VM, the ISO it installs from;
     * no address, so no HTTPS either.
     *
     * AIDEV-NOTE: the ISO entry carries no showWhen on the image origin: the handler's kind-narrowed host pick replaces
     * entries through InstanceTemplateHandlers.replacing, which carries no conditions. Its help says when it applies
     * and the handler refuses an ISO for any other origin by name.
     */
    private static final FormSpec MACHINE_INPUT = FormSpec.builder()
        .add(InstanceTemplateOperations.NAME)
        .add(declaredHostPick())
        .add(InstanceModel.KIND)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.SETTINGS))
        .add(Select.of(InstanceOperations.INSTALL_MEDIA)
            .options(OptionSource.dynamic(context -> new InstallMedia().optionsEverywhere()))
            .emptyReason(context -> InstallMedia.noneReason(null))
            .help(record -> HohenheimMicrocopy.INSTANCE_DEVICE.of("install_media_wizard_help"))
            .build())
        .section(RUNS_ON_HOST)
        .step(FormStep.of("where", STEP_WHERE, InstanceTemplateOperations.NAME.getName(),
            InstanceTemplateOperations.SERVER_ID.getName(), InstanceModel.KIND.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_machine_where_lead")))
        .step(FormStep.of("settings", STEP_OPTIONS, InstanceModel.SETTINGS.getName(),
                InstanceOperations.INSTALL_MEDIA.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_machine_settings_lead"))
            .summarizedBy(answers -> kindSummary(answers, PutOnlineReach.NONE)))
        .build();

    /**
     * A service of one kind: where it runs, its kind's own settings, then its Ports step: the ports as rows and the
     * game address. No address and no HTTPS: it is reached on its ports.
     *
     * AIDEV-NOTE: the Ports step owns the ports alone. The settings entry declared here is the kind's whole schema;
     * the handler's input scope (PutOnlineHandlers.workloadServiceScope) swaps it for the same entry omitting the
     * settings the kind publishes its ports through (InstanceKindHandler.portSettings), and the run writes the rows
     * into the published ports.
     */
    private static final FormSpec WORKLOAD_SERVICE_INPUT = FormSpec.builder()
        .add(InstanceTemplateOperations.NAME)
        .add(declaredHostPick())
        .add(InstanceModel.KIND)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.SETTINGS))
        .add(SERVICE_PORTS)
        .add(GAME_ADDRESS)
        .section(RUNS_ON_HOST)
        .step(FormStep.of("where", STEP_WHERE, InstanceTemplateOperations.NAME.getName(),
            InstanceTemplateOperations.SERVER_ID.getName(), InstanceModel.KIND.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_service_where_lead")))
        .step(FormStep.of("settings", STEP_OPTIONS, InstanceModel.SETTINGS.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_settings_lead")))
        .step(FormStep.of(PORTS, STEP_PORTS, PORTS, GAME_ADDRESS.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_service_ports_lead"))
            .summarizedWithItsOwn(PutOnline::workloadServiceSummary))
        .build();

    /**
     * What the HTTPS step answered: the certificate choice, an uploaded chain and key, and the access list.
     *
     * @param choice        null for a value the vocabulary does not know (the select refuses it first)
     * @param privateKeyPem a secret, never spelled in a log, a receipt or the run
     */
    public record HttpsAnswer(@Nullable HttpsChoice choice, @Nullable String certificatePem,
                              @Nullable Secret privateKeyPem, @Nullable Integer accessListId) {
    }

    /**
     * The template's own create (named as its form names it), its address, its HTTPS choice and access list.
     *
     * @param certificate_pem the HTTPS step's entries are spelled as their form fields, which the boot check holds
     *                        every record component to
     */
    public record FromTemplate(@Nullable String name, @Nullable String hostname, @Nullable Integer serverId,
                               @Nullable Integer projectId, @Nullable Integer environmentId,
                               @NonNull Map<String, Object> variables, @Nullable String https,
                               @Nullable String certificate_pem, @Nullable Secret private_key_pem,
                               @Nullable Integer access_list_id) {

        /** @return the template create funnel's own input */
        public InstanceTemplateOperations.@NonNull CreateFromTemplate create() {
            return new InstanceTemplateOperations.CreateFromTemplate(this.name, this.serverId, this.projectId,
                this.environmentId, this.variables);
        }

        /** @return what its HTTPS step answered */
        public @NonNull HttpsAnswer httpsAnswer() {
            return new HttpsAnswer(HttpsChoice.ofToken(this.https), this.certificate_pem, this.private_key_pem,
                this.access_list_id);
        }
    }

    /**
     * A service template's create (named as its form names it), the host port per declared port, its game address.
     *
     * @param game_address spelled as its form field, which the boot check holds every record component to
     */
    public record Service(@Nullable String name, @Nullable Integer serverId, @Nullable Integer projectId,
                          @Nullable Integer environmentId, @NonNull Map<String, Object> variables,
                          @NonNull Map<String, Object> ports, @Nullable String game_address) {

        /** @return the template create funnel's own input */
        public InstanceTemplateOperations.@NonNull CreateFromTemplate create() {
            return new InstanceTemplateOperations.CreateFromTemplate(this.name, this.serverId, this.projectId,
                this.environmentId, this.variables);
        }
    }

    /** An address that needs no workload: what it serves (the site's upstream kind and settings) and its HTTPS. */
    public record Address(@Nullable String name, @Nullable String hostname, @Nullable String upstream_kind,
                          @Nullable Map<String, Object> settings, @Nullable String https,
                          @Nullable String certificate_pem, @Nullable Secret private_key_pem,
                          @Nullable Integer access_list_id) {

        /** @return what its HTTPS step answered */
        public @NonNull HttpsAnswer httpsAnswer() {
            return new HttpsAnswer(HttpsChoice.ofToken(this.https), this.certificate_pem, this.private_key_pem,
                this.access_list_id);
        }
    }

    /**
     * A TCP/UDP stream: its name, its upstream, its public port and protocol, and its access list.
     *
     * @param forward_host spelled as its form field, which the boot check holds every record component to
     */
    public record Stream(@Nullable String name, @Nullable String forward_host, @Nullable Integer forward_port,
                         @Nullable Integer listen_port, @Nullable String protocol, @Nullable Integer access_list_id) {

        /** @return the stream site's settings */
        public @NonNull Map<String, Object> settings() {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put(StreamUpstreamKind.LISTEN_PORT.getName(), this.listen_port);
            settings.put(StreamUpstreamKind.PROTOCOL.getName(), this.protocol);
            settings.put(StreamUpstreamKind.FORWARD_HOST.getName(), this.forward_host);
            settings.put(StreamUpstreamKind.FORWARD_PORT.getName(), this.forward_port);
            return settings;
        }
    }

    /** A workload of one kind: its name, host (null: placement picks), settings, address and HTTPS choice. */
    public record Workload(@Nullable String name, @Nullable String hostname, @Nullable Integer serverId,
                           @Nullable String kind, @Nullable Map<String, Object> settings, @Nullable String https,
                           @Nullable String certificate_pem, @Nullable Secret private_key_pem,
                           @Nullable Integer access_list_id) {

        /** @return what its HTTPS step answered */
        public @NonNull HttpsAnswer httpsAnswer() {
            return new HttpsAnswer(HttpsChoice.ofToken(this.https), this.certificate_pem, this.private_key_pem,
                this.access_list_id);
        }
    }

    /** A machine of one kind: its name, host (null: placement picks), settings and, for an empty VM, its ISO. */
    public record Machine(@Nullable String name, @Nullable Integer serverId, @Nullable String kind,
                          @Nullable Map<String, Object> settings, @Nullable String installMedia) {
    }

    /**
     * A service of one kind: its name, host (null: placement picks), settings, the ports it publishes and its game
     * address.
     *
     * @param ports        the Ports step's rows, as its item list coerced them
     * @param game_address spelled as its form field, which the boot check holds every record component to
     */
    public record WorkloadService(@Nullable String name, @Nullable Integer serverId, @Nullable String kind,
                                  @Nullable Map<String, Object> settings, @NonNull List<Map<String, Object>> ports,
                                  @Nullable String game_address) {
    }

    /** Puts one template online: the app, its address, its certificate, then the first deploy. */
    public static final Operation<Row, FromTemplate, Integer> PUT_ONLINE =
        Operation.declare(HohenheimIds.id("put_online"))
            .label(HohenheimMicrocopy.PUT_ONLINE.of("put_online"))
            .happened(HohenheimMicrocopy.PUT_ONLINE.of("happened"))
            .icon(Icon.of("rocket"))
            .one(InstanceTemplateOperations.TEMPLATE)
            .gate(OperationGate.open())
            .input(OperationInput.of(TEMPLATE_INPUT, FromTemplate.class, values -> new FromTemplate(
                values.get(InstanceTemplateOperations.NAME), values.get(HOSTNAME),
                values.get(InstanceTemplateOperations.SERVER_ID), values.get(InstanceTemplateOperations.PROJECT_ID),
                values.get(InstanceTemplateOperations.ENVIRONMENT_ID),
                InstanceTemplateOperations.variables(values.get(InstanceTemplateOperations.VARIABLES)),
                values.get(HTTPS), values.get(CertificateModel.CERTIFICATE_PEM),
                secretOf(values.get(CertificateModel.PRIVATE_KEY_PEM.getName())),
                values.get(SiteModel.ACCESS_LIST_ID))))
            .result(Integer.class)
            .rateLimit(HohenheimEndpoints.INSTANCE_CREATE_LIMIT)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(OperationCommand.perSubject().execution(CommandExecution.OUTSIDE_TRANSACTION))
            .background(APP_STEPS)
            .register();

    /** Puts one service template online, reached on its ports: the app, its ports, its game address, its start. */
    public static final Operation<Row, Service, Integer> PUT_SERVICE_ONLINE =
        Operation.declare(HohenheimIds.id("put_service_online"))
            .label(HohenheimMicrocopy.PUT_ONLINE.of("put_online"))
            .happened(HohenheimMicrocopy.PUT_ONLINE.of("happened"))
            .icon(Icon.of("rocket"))
            .one(InstanceTemplateOperations.TEMPLATE)
            .gate(OperationGate.open())
            .input(OperationInput.of(SERVICE_INPUT, Service.class, values -> new Service(
                values.get(InstanceTemplateOperations.NAME), values.get(InstanceTemplateOperations.SERVER_ID),
                values.get(InstanceTemplateOperations.PROJECT_ID),
                values.get(InstanceTemplateOperations.ENVIRONMENT_ID),
                InstanceTemplateOperations.variables(values.get(InstanceTemplateOperations.VARIABLES)),
                Objects.requireNonNullElse(RawValues.mapOrNull(values.get(PORTS)), Map.of()),
                values.get(GAME_ADDRESS))))
            .result(Integer.class)
            .rateLimit(HohenheimEndpoints.INSTANCE_CREATE_LIMIT)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(OperationCommand.perSubject().execution(CommandExecution.OUTSIDE_TRANSACTION))
            .background(SERVICE_STEPS)
            .register();

    /** Puts one address online that needs no workload: its website, then its certificate. */
    public static final Operation<Void, Address, Integer> PUT_ADDRESS_ONLINE =
        Operation.declare(HohenheimIds.id("put_address_online"))
            .label(HohenheimMicrocopy.PUT_ONLINE.of("put_online"))
            .happened(HohenheimMicrocopy.PUT_ONLINE.of("happened"))
            .icon(Icon.of("rocket"))
            .noSubject()
            .gate(OperationGate.open())
            .input(OperationInput.of(ADDRESS_INPUT, Address.class, values -> new Address(values.get(SiteModel.NAME),
                values.get(HOSTNAME), values.get(SiteModel.UPSTREAM_KIND),
                RawValues.mapOrNull(values.get(SiteModel.SETTINGS.getName())), values.get(HTTPS),
                values.get(CertificateModel.CERTIFICATE_PEM),
                secretOf(values.get(CertificateModel.PRIVATE_KEY_PEM.getName())),
                values.get(SiteModel.ACCESS_LIST_ID))))
            .result(Integer.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(CmsCommands.EXTERNAL)
            .background(ADDRESS_STEPS)
            .register();

    /** Puts one TCP/UDP stream online: its public port claimed, then forwarded by the proxy. */
    public static final Operation<Void, Stream, Integer> PUT_STREAM_ONLINE =
        Operation.declare(HohenheimIds.id("put_stream_online"))
            .label(HohenheimMicrocopy.PUT_ONLINE.of("put_online"))
            .happened(HohenheimMicrocopy.PUT_ONLINE.of("happened"))
            .icon(Icon.of("rocket"))
            .noSubject()
            .gate(OperationGate.open())
            .input(OperationInput.of(STREAM_INPUT, Stream.class, values -> new Stream(values.get(SiteModel.NAME),
                values.get(StreamUpstreamKind.FORWARD_HOST), values.get(StreamUpstreamKind.FORWARD_PORT),
                values.get(StreamUpstreamKind.LISTEN_PORT), values.get(StreamUpstreamKind.PROTOCOL),
                values.get(SiteModel.ACCESS_LIST_ID))))
            .result(Integer.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(CmsCommands.EXTERNAL)
            .background(STREAM_STEPS)
            .register();

    /** Puts one workload online from its kind's own settings: the app, its website, its certificate, its start. */
    public static final Operation<Void, Workload, Integer> PUT_WORKLOAD_ONLINE =
        Operation.declare(HohenheimIds.id("put_workload_online"))
            .label(HohenheimMicrocopy.PUT_ONLINE.of("put_online"))
            .happened(HohenheimMicrocopy.PUT_ONLINE.of("happened"))
            .icon(Icon.of("rocket"))
            .noSubject()
            .gate(OperationGate.open())
            .input(OperationInput.of(WORKLOAD_INPUT, Workload.class, values -> new Workload(
                values.get(InstanceTemplateOperations.NAME), values.get(HOSTNAME),
                values.get(InstanceTemplateOperations.SERVER_ID),
                values.get(InstanceModel.KIND), RawValues.mapOrNull(values.get(InstanceModel.SETTINGS.getName())),
                values.get(HTTPS), values.get(CertificateModel.CERTIFICATE_PEM),
                secretOf(values.get(CertificateModel.PRIVATE_KEY_PEM.getName())),
                values.get(SiteModel.ACCESS_LIST_ID))))
            .result(Integer.class)
            .rateLimit(HohenheimEndpoints.INSTANCE_CREATE_LIMIT)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(CmsCommands.EXTERNAL)
            .background(WORKLOAD_STEPS)
            .register();

    /** Puts one machine online from its kind's own settings: the machine, then its first start. */
    public static final Operation<Void, Machine, Integer> PUT_MACHINE_ONLINE =
        Operation.declare(HohenheimIds.id("put_machine_online"))
            .label(HohenheimMicrocopy.PUT_ONLINE.of("put_online"))
            // A machine is created and started, never "put online": it gets no address and no certificate.
            .happened(HohenheimMicrocopy.PUT_ONLINE.of("machine_happened"))
            .icon(Icon.of("rocket"))
            .noSubject()
            .gate(OperationGate.open())
            .input(OperationInput.of(MACHINE_INPUT, Machine.class, values -> new Machine(
                values.get(InstanceTemplateOperations.NAME), values.get(InstanceTemplateOperations.SERVER_ID),
                values.get(InstanceModel.KIND), RawValues.mapOrNull(values.get(InstanceModel.SETTINGS.getName())),
                values.get(InstanceOperations.INSTALL_MEDIA))))
            .result(Integer.class)
            .rateLimit(HohenheimEndpoints.INSTANCE_CREATE_LIMIT)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(CmsCommands.EXTERNAL)
            .background(MACHINE_STEPS)
            .register();

    /**
     * Puts one service online from its kind's own settings, reached on the ports it publishes: the app, its ports,
     * its game address, its start.
     */
    public static final Operation<Void, WorkloadService, Integer> PUT_WORKLOAD_SERVICE_ONLINE =
        Operation.declare(HohenheimIds.id("put_workload_service_online"))
            .label(HohenheimMicrocopy.PUT_ONLINE.of("put_online"))
            .happened(HohenheimMicrocopy.PUT_ONLINE.of("happened"))
            .icon(Icon.of("rocket"))
            .noSubject()
            .gate(OperationGate.open())
            .input(OperationInput.of(WORKLOAD_SERVICE_INPUT, WorkloadService.class, values -> new WorkloadService(
                values.get(InstanceTemplateOperations.NAME), values.get(InstanceTemplateOperations.SERVER_ID),
                values.get(InstanceModel.KIND), RawValues.mapOrNull(values.get(InstanceModel.SETTINGS.getName())),
                RawValues.list(values.get(PORTS)).stream().map(RawValues::map).toList(),
                values.get(GAME_ADDRESS))))
            .result(Integer.class)
            .rateLimit(HohenheimEndpoints.INSTANCE_CREATE_LIMIT)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(CmsCommands.EXTERNAL)
            .background(WORKLOAD_SERVICE_STEPS)
            .register();

    private PutOnline() {
    }

    /** Forces the declarations to register before boot verifies their handlers. */
    public static void init() {
        PutOnlineHandlers.init();
    }

    /**
     * The journey steps every flow shares, in order, then the run page: what the chooser's stepper shows while what is
     * put online, and so how it is reached, is not chosen yet. Read off the flows' own declared forms.
     */
    static @NonNull List<Microcopy> sharedSteps() {
        List<Microcopy> shared = null;
        for (Operation<?, ?, ?> flow : List.of(PUT_ONLINE, PUT_SERVICE_ONLINE, PUT_ADDRESS_ONLINE, PUT_WORKLOAD_ONLINE,
                PUT_WORKLOAD_SERVICE_ONLINE, PUT_MACHINE_ONLINE, PUT_STREAM_ONLINE)) {
            List<Microcopy> journey = new ArrayList<>(BEFORE_FORM);
            flow.input().form().declaredSteps().forEach(step -> journey.add(step.label()));
            if (shared == null) {
                shared = journey;
            } else {
                shared.retainAll(journey);
            }
        }
        List<Microcopy> steps = new ArrayList<>(Objects.requireNonNull(shared));
        steps.add(STEP_LIVE);
        return steps;
    }

    /** @return a run's title from the name its Where step was given ("Putting Shop online"), null without one */
    private static @NonNull Function<Map<String, Object>, @Nullable Microcopy> titleFrom(@NonNull String nameField) {
        return input -> {
            Object name = input.get(nameField);
            return name == null || String.valueOf(name).isBlank() ? null
                : HohenheimMicrocopy.PUT_ONLINE.of("run_heading").withArg("name", String.valueOf(name));
        };
    }

    /** @return whether the upstream kind with this stored id may be put online as an app of its own */
    static boolean offeredAsApp(@Nullable String kind) {
        Identifier id = kind == null ? null : Identifier.tryParse(kind);
        UpstreamKindInfo info = id == null ? null : UpstreamKinds.REGISTRY.get(id);
        return info != null && info.offeredAsApp();
    }

    /** @return how an address of the upstream kind with this stored id is reached, null for a kind not known */
    static @Nullable PutOnlineReach addressReachOf(@Nullable String kind) {
        Identifier id = kind == null ? null : Identifier.tryParse(kind);
        UpstreamKindInfo info = id == null ? null : UpstreamKinds.REGISTRY.get(id);
        return info == null ? null : info.putOnlineReach();
    }

    /**
     * @return the ways a kind with this stored id is put online from its own settings, the kind's own card's first;
     *         empty for a kind the chooser does not offer
     */
    static @NonNull List<PutOnlineReach> reachesOf(@Nullable String kind) {
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        return handler == null || handler.putOnlineGroup() == null ? List.of() : handler.putOnlineReaches();
    }

    /** @return whether a kind with this stored id is put online from its own settings, reached this way */
    static boolean offeredWith(@Nullable String kind, @NonNull PutOnlineReach reach) {
        return reachesOf(kind).contains(reach);
    }

    /**
     * @return a kind as a flow reaching it this way names it: its own label on its own way (its first reach), else
     *         the chooser card's words for that way ("Docker container on its own ports")
     */
    static @NonNull Microcopy namedAs(@Nullable String kind, @NonNull Microcopy label, @NonNull PutOnlineReach reach) {
        List<PutOnlineReach> reaches = reachesOf(kind);
        return reaches.isEmpty() || reaches.getFirst() == reach ? label : reach.cardLabel().withArg("kind", label);
    }

    /** @return how a template's workload is reached, from its kind and its settings baseline */
    static @NonNull PutOnlineReach reachOf(@NonNull Row template) {
        InstanceKindHandler handler = InstanceKinds.getHandler(String.valueOf((Object) template.get(
            InstanceTemplateModel.KIND)));
        return handler == null ? PutOnlineReach.WEBSITE : handler.reachOf(templateSettings(template));
    }

    /** @return a template's settings baseline, empty when it has none */
    @SuppressWarnings("unchecked")
    static @NonNull Map<String, Object> templateSettings(@NonNull Row template) {
        return template.get(InstanceTemplateModel.SETTINGS) instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
    }

    /** @return the ports a template declares, in declaration order, without host ports */
    static @NonNull List<PublishedPort> declaredPorts(@NonNull Row template) {
        return PublishedPort.of(templateSettings(template));
    }

    /** @return a port's purpose in words, or its own name for one the purpose vocabulary does not know */
    static @NonNull Microcopy portLabel(@NonNull PublishedPort port) {
        PortPurpose purpose = port.purpose();
        return purpose == PortPurpose.OTHER ? Microcopy.literal(port.name()) : purpose.label();
    }

    /** @return one Ports row as the port it publishes, null while its name or its container port is missing */
    static @Nullable PublishedPort rowPort(@NonNull Map<String, Object> row) {
        PortPurpose purpose = PortPurpose.forName(RawValues.nonBlankString(row.get(PORT_PURPOSE.getName())));
        String name = purpose == PortPurpose.OTHER ? RawValues.nonBlankString(row.get(PORT_NAME.getName()))
            : purpose.token();
        Integer container = RawValues.parsedInt(row.get(PORT_CONTAINER.getName()));
        if (name == null || container == null) {
            return null;
        }
        String protocol = PublishedPort.UDP.equals(row.get(PORT_PROTOCOL.getName())) ? PublishedPort.UDP
            : PublishedPort.TCP;
        return new PublishedPort(name.trim(), RawValues.parsedInt(row.get(PORT_HOST.getName())), container, protocol,
            false);
    }

    /**
     * What a kind-led service's Ports step shows: the kind as its card names it, its name and host, then each row
     * typed on this same step with its host port (asked again as the rows change).
     */
    private static @NonNull List<SummaryLine> workloadServiceSummary(@NonNull StepAnswers answers) {
        List<SummaryLine> lines = kindSummary(answers, PutOnlineReach.PORTS);
        if (answers.get(PORTS) instanceof List<?> rows) {
            for (Object row : rows) {
                PublishedPort port = row instanceof Map<?, ?> map ? rowPort(RawValues.map(map)) : null;
                if (port == null) {
                    continue;
                }
                String spelled = port.containerPort() + "/" + port.protocol();
                lines.add(new SummaryLine(portLabel(port), port.hostPort() == null
                    ? HohenheimMicrocopy.PUT_ONLINE.of("summary_port_row_free").withArg("port", spelled)
                    : HohenheimMicrocopy.PUT_ONLINE.of("summary_port_row").withArg("port", spelled)
                        .withArg("host", String.valueOf(port.hostPort()))));
            }
        }
        return lines;
    }

    /**
     * What a service's Ports step shows of Where: its name, where it runs, then each port the chosen template declares.
     *
     * AIDEV-NOTE: the host ports and the game address are asked on that same step, below this summary, so neither is
     * an answer here: a port's line says how its host port is chosen instead of naming one.
     */
    private static @NonNull List<SummaryLine> serviceSummary(@NonNull StepAnswers answers) {
        List<SummaryLine> lines = new ArrayList<>();
        String name = answers.text(InstanceTemplateOperations.NAME.getName());
        if (name != null) {
            lines.add(new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_name"), Microcopy.literal(name)));
        }
        lines.add(runsOnLine(answers));
        Row template = answers.subject(Row.class);
        if (template != null) {
            for (PublishedPort port : declaredPorts(template)) {
                lines.add(new SummaryLine(portLabel(port), HohenheimMicrocopy.PUT_ONLINE.of("summary_port")
                    .withArg("port", port.containerPort() + "/" + port.protocol())));
            }
        }
        return lines;
    }

    /** What a template's HTTPS step shows of Where and Options in its summary. */
    private static @NonNull List<SummaryLine> templateSummary(@NonNull StepAnswers answers) {
        List<SummaryLine> lines = new ArrayList<>();
        String name = answers.text(InstanceTemplateOperations.NAME.getName());
        if (name != null) {
            lines.add(new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_name"), Microcopy.literal(name)));
        }
        String hostname = answers.text(HOSTNAME.getName());
        lines.add(addressLine(hostname));
        lines.add(runsOnLine(answers));
        if (hostname != null) {
            lines.add(reachLine(hostname));
        }
        return lines;
    }

    /** What an address's HTTPS step shows of Where: what it serves, its name and address, and where that points. */
    private static @NonNull List<SummaryLine> addressSummary(@NonNull StepAnswers answers) {
        String kind = answers.text(SiteModel.UPSTREAM_KIND.getName());
        Identifier kindId = kind == null ? null : Identifier.tryParse(kind);
        UpstreamKindInfo info = kindId == null ? null : UpstreamKinds.REGISTRY.get(kindId);
        return servedSummary(info == null ? null : info.getLabel(), answers.text(SiteModel.NAME.getName()),
            answers.text(ADDRESS_HOSTNAME.getName()), null);
    }

    /** What a workload's HTTPS step shows of Where: its kind, its name and address, and where that points. */
    private static @NonNull List<SummaryLine> workloadSummary(@NonNull StepAnswers answers) {
        InstanceKindHandler handler = InstanceKinds.getHandler(answers.text(InstanceModel.KIND.getName()));
        return servedSummary(handler == null ? null : handler.getLabel(),
            answers.text(InstanceTemplateOperations.NAME.getName()), answers.text(HOSTNAME.getName()),
            runsOnLine(answers));
    }

    /** What a kind-led step shows of Where: the kind as this flow names it (the chooser's card), its name and host. */
    private static @NonNull List<SummaryLine> kindSummary(@NonNull StepAnswers answers,
                                                          @NonNull PutOnlineReach reach) {
        List<SummaryLine> lines = new ArrayList<>();
        String kind = answers.text(InstanceModel.KIND.getName());
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        if (handler != null) {
            lines.add(new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_serves"),
                namedAs(kind, handler.getLabel(), reach)));
        }
        String name = answers.text(InstanceTemplateOperations.NAME.getName());
        if (name != null) {
            lines.add(new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_name"), Microcopy.literal(name)));
        }
        lines.add(runsOnLine(answers));
        return lines;
    }

    /** What it serves, its name, its address, where it runs when that is asked, then where the address points. */
    private static @NonNull List<SummaryLine> servedSummary(@Nullable Microcopy what, @Nullable String name,
                                                            @Nullable String hostname, @Nullable SummaryLine runsOn) {
        List<SummaryLine> lines = new ArrayList<>();
        if (what != null) {
            lines.add(new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_serves"), what));
        }
        if (name != null) {
            lines.add(new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_name"), Microcopy.literal(name)));
        }
        lines.add(addressLine(hostname));
        if (runsOn != null) {
            lines.add(runsOn);
        }
        if (hostname != null) {
            lines.add(reachLine(hostname));
        }
        return lines;
    }

    private static @NonNull SummaryLine addressLine(@Nullable String hostname) {
        return new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_address"),
            hostname == null ? HohenheimMicrocopy.PUT_ONLINE.of("summary_address_none") : Microcopy.literal(hostname));
    }

    /** The host the answers chose, or that placement picks one. */
    private static @NonNull SummaryLine runsOnLine(@NonNull StepAnswers answers) {
        Integer serverId = answers.get(InstanceTemplateOperations.SERVER_ID.getName(), Integer.class);
        return new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_runs_on"),
            serverId == null ? HohenheimMicrocopy.PUT_ONLINE.of("summary_runs_on_auto")
                : Microcopy.literal(serverName(serverId)));
    }

    /**
     * Whether the address points here yet: a notice, never a refusal. The certificate is ordered after the website
     * exists and fails on its own when the name points elsewhere; this only says so before the operator commits.
     */
    private static @NonNull SummaryLine reachLine(@NonNull String hostname) {
        HostnameReach.Reach reach = HostnameReach.recent(hostname);
        Microcopy verdict = switch (reach.verdict()) {
            case POINTS_HERE -> HohenheimMicrocopy.PUT_ONLINE.of("reach_here");
            case POINTS_ELSEWHERE -> HohenheimMicrocopy.PUT_ONLINE.of("reach_elsewhere");
            case UNRESOLVED -> HohenheimMicrocopy.PUT_ONLINE.of("reach_unresolved");
            case UNKNOWN -> HohenheimMicrocopy.PUT_ONLINE.of("reach_unknown");
            case CHECKING -> HohenheimMicrocopy.PUT_ONLINE.of("reach_checking");
            case NOT_CHECKED -> HohenheimMicrocopy.PUT_ONLINE.of("reach_not_checked");
        };
        return new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("reach_label"), verdict.withArg("hostname", hostname)
            .withArg("addresses", String.join(", ", reach.addresses())));
    }

    private static @NonNull String serverName(int serverId) {
        Row server = Models.get(ServerModel.class).findById(serverId);
        return server == null ? "#" + serverId : String.valueOf((Object) server.get(ServerModel.NAME));
    }

    /**
     * The host pick both kind-led inputs declare: THE kind-judged host pick ({@link InstanceParts#hostPick}), every
     * host offered and each one the chosen kind cannot be placed on disabled with the placement gate's reason.
     */
    private static @NonNull Select<Integer> declaredHostPick() {
        return InstanceParts.hostPick(InstanceTemplateOperations.SERVER_ID);
    }

    /** The HTTPS step's cards, one per {@link HttpsChoice}, in the vocabulary's order. */
    private static @NonNull Select<String> httpsChoice() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (HttpsChoice choice : HttpsChoice.values()) {
            options.add(FieldOption.of(choice.token(), choice.label()).withDescription(choice.help()));
        }
        return Select.of(HTTPS)
            .options(OptionSource.of(options))
            .presentation(Select.Presentation.CARDS)
            .clearable(false)
            .build();
    }

    /** @return the tokens of the choices that ask for an uploaded chain and key */
    private static @NonNull String @NonNull [] uploadTokens() {
        return Arrays.stream(HttpsChoice.values()).filter(HttpsChoice::takesUpload)
            .map(HttpsChoice::token).toArray(String[]::new);
    }

    /** The HTTPS step every address flow ends with: the certificate choice, an upload, the access list. */
    private static @NonNull FormStep httpsStep(@NonNull StepSummary summary) {
        return FormStep.of("https", STEP_HTTPS, HTTPS.getName(), CertificateModel.CERTIFICATE_PEM.getName(),
                CertificateModel.PRIVATE_KEY_PEM.getName(), SiteModel.ACCESS_LIST_ID.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_https_lead"))
            .summarizedBy(summary);
    }

    /** @return a secret entry's coerced value, null when none was given */
    private static @Nullable Secret secretOf(@Nullable Object value) {
        return value instanceof Secret secret ? secret : null;
    }

    /** @return one option per port purpose, in the vocabulary's order */
    private static @NonNull List<FieldOption<String>> purposeOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (PortPurpose purpose : PortPurpose.values()) {
            options.add(FieldOption.of(purpose.token(), purpose.label()));
        }
        return options;
    }
}
