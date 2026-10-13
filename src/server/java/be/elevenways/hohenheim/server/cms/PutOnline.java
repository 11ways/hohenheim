package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.server.instance.InstallMedia;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.tls.HostnameReach;
import be.elevenways.hohenheim.upstream.UpstreamKindInfo;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.FieldFormEntryDefaults;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.FormStep;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.edit.StepAnswers;
import be.elevenways.zenit.common.edit.SummaryLine;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationSteps;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "Put something online": one flow that creates an app, its address and its certificate, as background operations
 * behind one wizard page ({@link PutOnlinePage}).
 *
 * AIDEV-NOTE: five operations because the input differs by WHAT is put online. A template's form is that template's
 * own variables, resolved per selected template (the subject), so {@link #PUT_ONLINE} takes the template as its
 * subject, exactly as the create-from-template lane does; a template whose workload is reached on its ports
 * ({@code PutOnlineReach.PORTS}, a game server) is {@link #PUT_SERVICE_ONLINE} instead, with a Ports step and an
 * optional game address where the others ask an address and HTTPS. An address that needs no workload (a redirect, an
 * existing service, static files, TLS passthrough) has no subject; {@link #PUT_ADDRESS_ONLINE} asks the site form's upstream
 * kind and settings. A workload of a kind that serves a website (a Git app, a container image) has no subject either;
 * {@link #PUT_WORKLOAD_ONLINE} asks that kind's own settings, the instance form's. A machine of a kind that serves no
 * website (a virtual machine, a Linux container) is {@link #PUT_MACHINE_ONLINE}: the same kind's own settings, without
 * the address and HTTPS steps. The workload ones end in the same Website and Certificate steps (PutOnlineHandlers),
 * so the going-live half is one implementation, and every run starts what it made the same way. All run in the
 * background: their run page is the "Going live" step.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class PutOnline {

    /** The HTTPS choice: a Let's Encrypt certificate ordered once the address exists. */
    public static final String HTTPS_AUTOMATIC = "automatic";

    /** The HTTPS choice: no order now; the address answers over HTTP until a certificate is added. */
    public static final String HTTPS_LATER = "later";

    /** The address visitors type. Named like the site create's first hostname, so route refusals path on it. */
    public static final StringField HOSTNAME = StringField.builder(SiteDomainModel.HOSTNAME.getName())
        .label(HohenheimMicrocopy.PUT_ONLINE.of("address"))
        .help(HohenheimMicrocopy.PUT_ONLINE.of("address_help"))
        .placeholder("shop.example.com")
        .build();

    /** The same address for the address kinds, which have nothing to serve without one (so no "leave it empty"). */
    public static final StringField ADDRESS_HOSTNAME = StringField.builder(SiteDomainModel.HOSTNAME.getName())
        .label(HohenheimMicrocopy.PUT_ONLINE.of("address"))
        .help(HohenheimMicrocopy.PUT_ONLINE.of("address_help_required"))
        .placeholder("shop.example.com")
        .build();

    public static final StringField HTTPS = StringField.builder("https")
        .label(HohenheimMicrocopy.PUT_ONLINE.of("certificate")).build();

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

    /** The run's steps for a template: the app, its install, its website, its certificate, then live. */
    private static final OperationSteps APP_STEPS = OperationSteps.of(
        OperationSteps.step("app", HohenheimMicrocopy.PUT_ONLINE.of("run_app")),
        OperationSteps.step("install", HohenheimMicrocopy.PUT_ONLINE.of("run_install")),
        OperationSteps.step("website", HohenheimMicrocopy.PUT_ONLINE.of("run_website")),
        OperationSteps.step("certificate", HohenheimMicrocopy.PUT_ONLINE.of("run_certificate")),
        OperationSteps.step("live", HohenheimMicrocopy.PUT_ONLINE.of("run_live")))
        .inJourney(List.of(STEP_WHAT, STEP_WHERE, STEP_OPTIONS, STEP_HTTPS), STEP_LIVE);

    /** The run's steps for a service from a template: the app, its install, its ports, its game address, then live. */
    private static final OperationSteps SERVICE_STEPS = OperationSteps.of(
        OperationSteps.step("app", HohenheimMicrocopy.PUT_ONLINE.of("run_app")),
        OperationSteps.step("install", HohenheimMicrocopy.PUT_ONLINE.of("run_install")),
        OperationSteps.step("ports", HohenheimMicrocopy.PUT_ONLINE.of("run_ports")),
        OperationSteps.step("game_address", HohenheimMicrocopy.PUT_ONLINE.of("run_game_address")),
        OperationSteps.step("live", HohenheimMicrocopy.PUT_ONLINE.of("run_live")))
        .inJourney(List.of(STEP_WHAT, STEP_WHERE, STEP_OPTIONS, STEP_PORTS), STEP_LIVE);

    /** The run's steps for an address: its website and its certificate. */
    private static final OperationSteps ADDRESS_STEPS = OperationSteps.of(
        OperationSteps.step("website", HohenheimMicrocopy.PUT_ONLINE.of("run_website")),
        OperationSteps.step("certificate", HohenheimMicrocopy.PUT_ONLINE.of("run_certificate")))
        .inJourney(List.of(STEP_WHAT, STEP_WHERE, STEP_HTTPS), STEP_LIVE);

    /** The run's steps for a workload of one kind: the app, its website, its certificate, then live. */
    private static final OperationSteps WORKLOAD_STEPS = OperationSteps.of(
        OperationSteps.step("app", HohenheimMicrocopy.PUT_ONLINE.of("run_app")),
        OperationSteps.step("website", HohenheimMicrocopy.PUT_ONLINE.of("run_website")),
        OperationSteps.step("certificate", HohenheimMicrocopy.PUT_ONLINE.of("run_certificate")),
        OperationSteps.step("live", HohenheimMicrocopy.PUT_ONLINE.of("run_live")))
        .inJourney(List.of(STEP_WHAT, STEP_WHERE, STEP_OPTIONS, STEP_HTTPS), STEP_LIVE);

    /** The run's steps for a machine: the machine, in its group's words, then its first start. */
    private static final OperationSteps MACHINE_STEPS = OperationSteps.of(
        OperationSteps.step("app", PutOnlineGroup.MACHINE.words(HohenheimMicrocopy.PUT_ONLINE.of("run_app"))),
        OperationSteps.step("live", HohenheimMicrocopy.PUT_ONLINE.of("run_live")))
        .inJourney(List.of(STEP_WHAT, STEP_WHERE, STEP_OPTIONS), STEP_LIVE);

    private static final FormSpec TEMPLATE_INPUT = FormSpec.builder()
        .add(InstanceTemplateOperations.NAME)
        .add(HOSTNAME)
        .add(InstanceTemplateOperations.SERVER_ID)
        .add(InstanceTemplateOperations.PROJECT_ID)
        .add(InstanceTemplateOperations.ENVIRONMENT_ID)
        .add(Nested.of(InstanceTemplateOperations.VARIABLES).subSpec(FormSpec.builder().build()).build())
        .add(httpsChoice())
        .step(FormStep.of("where", STEP_WHERE, InstanceTemplateOperations.NAME.getName(), HOSTNAME.getName(),
            InstanceTemplateOperations.SERVER_ID.getName(), InstanceTemplateOperations.PROJECT_ID.getName(),
            InstanceTemplateOperations.ENVIRONMENT_ID.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_where_lead")))
        .step(new FormStep(InstanceTemplateOperations.VARIABLES, STEP_OPTIONS,
            HohenheimMicrocopy.PUT_ONLINE.of("step_options_lead"),
            List.of(InstanceTemplateOperations.VARIABLES)))
        .step(FormStep.of("https", STEP_HTTPS, HTTPS.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_https_lead"))
            .summarizedBy(PutOnline::templateSummary))
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
                .resolve(context).stream().filter(option -> offeredAsApp(option.value())).toList()))
            .presentation(Select.Presentation.CARDS)
            .clearable(false)
            .build())
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SETTINGS))
        .add(httpsChoice())
        .step(FormStep.of("where", STEP_WHERE, SiteModel.NAME.getName(), ADDRESS_HOSTNAME.getName(),
            SiteModel.UPSTREAM_KIND.getName(), SiteModel.SETTINGS.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_where_lead")))
        .step(FormStep.of("https", STEP_HTTPS, HTTPS.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_https_lead"))
            .summarizedBy(PutOnline::addressSummary))
        .build();

    private static final FormSpec WORKLOAD_INPUT = FormSpec.builder()
        .add(InstanceModel.NAME)
        .add(HOSTNAME)
        .add(declaredHostPick())
        .add(InstanceModel.KIND)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.SETTINGS))
        .add(httpsChoice())
        .step(FormStep.of("where", STEP_WHERE, InstanceModel.NAME.getName(), HOSTNAME.getName(),
            InstanceTemplateOperations.SERVER_ID.getName(), InstanceModel.KIND.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_where_lead")))
        .step(FormStep.of("settings", STEP_OPTIONS, InstanceModel.SETTINGS.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_settings_lead")))
        .step(FormStep.of("https", STEP_HTTPS, HTTPS.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_https_lead"))
            .summarizedBy(PutOnline::workloadSummary))
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
        .add(InstanceModel.NAME)
        .add(declaredHostPick())
        .add(InstanceModel.KIND)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.SETTINGS))
        .add(Select.of(InstanceOperations.INSTALL_MEDIA)
            .options(OptionSource.dynamic(context -> new InstallMedia().optionsEverywhere()))
            .emptyReason(context -> InstallMedia.noneReason(null))
            .help(record -> HohenheimMicrocopy.INSTANCE_DEVICE.of("install_media_wizard_help"))
            .build())
        .step(FormStep.of("where", STEP_WHERE, InstanceModel.NAME.getName(),
            InstanceTemplateOperations.SERVER_ID.getName(), InstanceModel.KIND.getName())
                .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_machine_where_lead")))
        .step(FormStep.of("settings", STEP_OPTIONS, InstanceModel.SETTINGS.getName(),
                InstanceOperations.INSTALL_MEDIA.getName())
            .describe(HohenheimMicrocopy.PUT_ONLINE.of("step_machine_settings_lead"))
            .summarizedBy(PutOnline::machineSummary))
        .build();

    /** The template's own create (named as its form names it), its address and its HTTPS choice. */
    public record FromTemplate(@Nullable String name, @Nullable String hostname, @Nullable Integer serverId,
                               @Nullable Integer projectId, @Nullable Integer environmentId,
                               @NonNull Map<String, Object> variables, @Nullable String https) {

        /** @return the template create funnel's own input */
        public InstanceTemplateOperations.@NonNull CreateFromTemplate create() {
            return new InstanceTemplateOperations.CreateFromTemplate(this.name, this.serverId, this.projectId,
                this.environmentId, this.variables);
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
                          @Nullable Map<String, Object> settings, @Nullable String https) {
    }

    /** A workload of one kind: its name, host (null: placement picks), settings, address and HTTPS choice. */
    public record Workload(@Nullable String name, @Nullable String hostname, @Nullable Integer serverId,
                           @Nullable String kind, @Nullable Map<String, Object> settings, @Nullable String https) {
    }

    /** A machine of one kind: its name, host (null: placement picks), settings and, for an empty VM, its ISO. */
    public record Machine(@Nullable String name, @Nullable Integer serverId, @Nullable String kind,
                          @Nullable Map<String, Object> settings, @Nullable String installMedia) {
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
                values.get(HTTPS))))
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
                RawValues.mapOrNull(values.get(SiteModel.SETTINGS.getName())), values.get(HTTPS))))
            .result(Integer.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(CmsCommands.EXTERNAL)
            .background(ADDRESS_STEPS)
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
                values.get(InstanceModel.NAME), values.get(HOSTNAME), values.get(InstanceTemplateOperations.SERVER_ID),
                values.get(InstanceModel.KIND), RawValues.mapOrNull(values.get(InstanceModel.SETTINGS.getName())),
                values.get(HTTPS))))
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
                values.get(InstanceModel.NAME), values.get(InstanceTemplateOperations.SERVER_ID),
                values.get(InstanceModel.KIND), RawValues.mapOrNull(values.get(InstanceModel.SETTINGS.getName())),
                values.get(InstanceOperations.INSTALL_MEDIA))))
            .result(Integer.class)
            .rateLimit(HohenheimEndpoints.INSTANCE_CREATE_LIMIT)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(CmsCommands.EXTERNAL)
            .background(MACHINE_STEPS)
            .register();

    private PutOnline() {
    }

    /** Forces the declarations to register before boot verifies their handlers. */
    public static void init() {
        PutOnlineHandlers.init();
    }

    /** @return whether the upstream kind with this stored id may be put online as an app of its own */
    static boolean offeredAsApp(@Nullable String kind) {
        Identifier id = kind == null ? null : Identifier.tryParse(kind);
        UpstreamKindInfo info = id == null ? null : UpstreamKinds.REGISTRY.get(id);
        return info != null && info.offeredAsApp();
    }

    /** @return whether a workload of the kind with this stored id is put online from its own settings and address */
    static boolean offeredAsWorkload(@Nullable String kind) {
        return reachOfKind(kind) == PutOnlineReach.WEBSITE;
    }

    /** @return whether a machine of the kind with this stored id is put online from its own settings, without one */
    static boolean offeredAsMachine(@Nullable String kind) {
        return reachOfKind(kind) == PutOnlineReach.NONE;
    }

    /** @return how a kind put online from its own (empty) settings is reached, or null for a kind not offered */
    private static @Nullable PutOnlineReach reachOfKind(@Nullable String kind) {
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        return handler == null || handler.putOnlineGroup() == null ? null : handler.reachOf(Map.of());
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

    /** What a service's Ports step shows of Where: its name, where it runs, its ports and its game address. */
    private static @NonNull List<SummaryLine> serviceSummary(@NonNull StepAnswers answers) {
        List<SummaryLine> lines = new ArrayList<>();
        String name = answers.text(InstanceTemplateOperations.NAME.getName());
        if (name != null) {
            lines.add(new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_name"), Microcopy.literal(name)));
        }
        lines.add(runsOnLine(answers));
        String address = answers.text(GAME_ADDRESS.getName());
        if (address != null) {
            lines.add(new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_game_address"),
                Microcopy.literal(address)));
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
        return servedSummary(handler == null ? null : handler.getLabel(), answers.text(InstanceModel.NAME.getName()),
            answers.text(HOSTNAME.getName()), runsOnLine(answers));
    }

    /** What a machine's settings step shows of Where: its kind, its name and where it runs. */
    private static @NonNull List<SummaryLine> machineSummary(@NonNull StepAnswers answers) {
        List<SummaryLine> lines = new ArrayList<>();
        InstanceKindHandler handler = InstanceKinds.getHandler(answers.text(InstanceModel.KIND.getName()));
        if (handler != null) {
            lines.add(new SummaryLine(HohenheimMicrocopy.PUT_ONLINE.of("summary_serves"), handler.getLabel()));
        }
        String name = answers.text(InstanceModel.NAME.getName());
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

    private static @NonNull Select<String> httpsChoice() {
        return Select.of(HTTPS)
            .options(OptionSource.of(
                FieldOption.of(HTTPS_AUTOMATIC, HohenheimMicrocopy.PUT_ONLINE.of("https_automatic"))
                    .withDescription(HohenheimMicrocopy.PUT_ONLINE.of("https_automatic_help")),
                FieldOption.of(HTTPS_LATER, HohenheimMicrocopy.PUT_ONLINE.of("https_later"))
                    .withDescription(HohenheimMicrocopy.PUT_ONLINE.of("https_later_help"))))
            .presentation(Select.Presentation.CARDS)
            .clearable(false)
            .build();
    }
}
