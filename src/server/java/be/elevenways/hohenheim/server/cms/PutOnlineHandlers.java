package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.instance.ImageOrigin;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstallMedia;
import be.elevenways.hohenheim.server.instance.InstanceDevices;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceTemplateHandlers;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.tls.UploadedCertificates;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.http.HostPattern;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import be.elevenways.zenit.server.operation.InputScope;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortPurpose;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.server.game.GameAddresses;
import be.elevenways.hohenheim.server.instance.PortPublications;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * The server half of "Put something online": each run composes the existing writers, one step after the other, and
 * reports each step as one progress part of its run.
 *
 * AIDEV-NOTE: nothing here is a second writer. The app is the template create funnel
 * ({@link InstanceTemplates#createFromTemplate}) or the same placed record write for a kind's own settings, the
 * install is {@link InstanceInstalls}, the website and its first
 * address are the site create ({@link SiteOperationHandlers#createSite}, in its own transaction so a refused address
 * rolls the site back), the certificate is the certificate operation's own order ({@link CertificateOperationHandlers})
 * and the first start is {@link InstanceService#deploy}. The ACME order runs after the website's transaction has
 * committed, never inside it. A step that cannot finish (Let's Encrypt off, a name that points elsewhere, databases
 * still provisioning) does not fail what already exists: the run ends with a message that says what is left, and the
 * app's own page (its health verdict) says the rest.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class PutOnlineHandlers {

    static {
        OperationHandlers.attach(PutOnline.PUT_ONLINE)
            .source(InstanceTemplateHandlers.selectableTemplates())
            .inputScope(InstanceTemplateHandlers::inputScope)
            .authorize(PutOnlineHandlers::addressAuthority)
            .handle(PutOnlineHandlers::putOnline);
        OperationHandlers.attach(PutOnline.PUT_SERVICE_ONLINE)
            .source(InstanceTemplateHandlers.selectableTemplates())
            .inputScope(PutOnlineHandlers::serviceScope)
            .authorize(PutOnlineHandlers::serviceAuthority)
            .handle(PutOnlineHandlers::putServiceOnline);
        OperationHandlers.attach(PutOnline.PUT_ADDRESS_ONLINE)
            .authorize(PutOnlineHandlers::admitAddress)
            .handle(PutOnlineHandlers::putAddressOnline);
        OperationHandlers.attach(PutOnline.PUT_WORKLOAD_ONLINE)
            .authorize(PutOnlineHandlers::admitWorkload)
            .handle(PutOnlineHandlers::putWorkloadOnline);
        OperationHandlers.attach(PutOnline.PUT_WORKLOAD_SERVICE_ONLINE)
            .inputScope(PutOnlineHandlers::workloadServiceScope)
            .authorize(PutOnlineHandlers::admitWorkloadService)
            .handle(PutOnlineHandlers::putWorkloadServiceOnline);
        OperationHandlers.attach(PutOnline.PUT_MACHINE_ONLINE)
            .authorize(PutOnlineHandlers::admitMachine)
            .handle(PutOnlineHandlers::putMachineOnline);
    }

    private PutOnlineHandlers() {
    }

    static void init() {
    }

    /**
     * Claiming an address is an operator act; anyone the template admits may put it online without one. An address
     * given is judged with its HTTPS step ({@link #httpsRefusal}).
     */
    private static @Nullable DomainRefusal addressAuthority(@NonNull Row template,
            PutOnline.@Nullable FromTemplate input,
                                                           @NonNull AccessContext access) {
        if (input == null || Texts.trimmedOrNull(input.hostname()) == null) {
            return null;
        }
        if (!HohenheimAccess.isAdmin(access)) {
            return new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "an address is given by an operator");
        }
        return httpsRefusal(input.hostname(), input.httpsAnswer(), access);
    }

    /**
     * In the request, before a run is recorded: the HTTPS step's answer for an address. An existing certificate must
     * cover it, a wildcard needs a parent domain, an upload is judged whole ({@link UploadedCertificates#check}) and
     * the access list must be one the caller may attach. Nothing is judged without an address.
     */
    private static @Nullable DomainRefusal httpsRefusal(@Nullable String address, PutOnline.@NonNull HttpsAnswer answer,
                                                        @NonNull AccessContext access) {
        String hostname = Texts.trimmedOrNull(address);
        if (hostname == null) {
            return null;
        }
        Integer list = answer.accessListId();
        if (list != null && !HohenheimAccess.canUseAccessList(access, list)) {
            return new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "access list " + list + " is not usable",
                SiteModel.ACCESS_LIST_ID.getName(), HohenheimMicrocopy.VIOLATIONS.of("tenant_access_list_not_usable"));
        }
        HttpsChoice choice = answer.choice();
        if (choice == null) {
            return null;
        }
        return switch (choice) {
            case AUTOMATIC, LATER -> null;
            case WILDCARD -> wildcardOf(hostname) != null ? null
                : new DomainRefusal(ZenitRefusalReason.INVALID, "no parent domain under " + hostname,
                    PutOnline.HTTPS.getName(), message("https_wildcard_apex").withArg("address", hostname));
            case EXISTING -> usableCovering(hostname) != null ? null
                : new DomainRefusal(ZenitRefusalReason.INVALID, "no certificate covers " + hostname,
                    PutOnline.HTTPS.getName(), message("https_existing_none").withArg("address", hostname));
            case UPLOAD -> {
                try {
                    UploadedCertificates.check(answer.certificatePem(), revealed(answer.privateKeyPem()), hostname);
                    yield null;
                } catch (Violations refused) {
                    yield refusedAs("certificate", refused);
                }
            }
        };
    }

    /**
     * @return the one-label wildcard covering the address and its siblings (zenit's {@link HostPattern} grammar), the
     *         address itself when it already is one, null when it has no parent domain below a public suffix
     */
    static @Nullable String wildcardOf(@NonNull String hostname) {
        HostPattern given = HostPattern.tryParse(hostname);
        if (given == null) {
            return null;
        }
        if (given.base() != null) {
            return given.text();
        }
        int dot = hostname.indexOf('.');
        String parent = dot < 0 ? "" : hostname.substring(dot + 1);
        return parent.indexOf('.') < 0 ? null : HostPattern.oneLabelUnder(parent, null).text();
    }

    /** @return the active, unexpired certificate Hohenheim holds that covers the address, null when none does */
    private static @Nullable Row usableCovering(@NonNull String hostname) {
        Row covering = CertificateCoverage.coveringCertificate(hostname);
        if (covering == null || !CertificateModel.STATUS_ACTIVE.equals(covering.get(CertificateModel.STATUS))) {
            return null;
        }
        Instant expires = covering.get(CertificateModel.EXPIRES_ON);
        return expires == null || expires.isAfter(Now.instant()) ? covering : null;
    }

    private static @Nullable String revealed(@Nullable Secret secret) {
        return secret == null ? null : secret.reveal();
    }

    /**
     * The template's own input ({@link InstanceTemplateHandlers#inputScope}) with its Ports step: one host port entry
     * per port the template declares, empty for one Hohenheim picks.
     */
    private static @NonNull InputScope serviceScope(@Nullable Row template, @NonNull FormSpec declared,
                                                    @Nullable AccessContext access) {
        InputScope scope = InstanceTemplateHandlers.inputScope(template, declared, access);
        if (template == null) {
            return scope;
        }
        FormSpec.Builder ports = FormSpec.builder();
        for (PublishedPort port : PutOnline.declaredPorts(template)) {
            ports.add(IntegerField.builder().name(port.name())
                .label(PutOnline.portLabel(port))
                .help(HohenheimMicrocopy.PUT_ONLINE.of("port_host_help")
                    .withArg("port", port.containerPort() + "/" + port.protocol()))
                .build());
        }
        return new InputScope(InstanceTemplateHandlers.replacing(scope.form(), Map.of(PutOnline.PORTS,
            Nested.of(PutOnline.PORTS).subSpec(ports.build()).build())), scope.serverDefaults());
    }

    /**
     * A kind-led service's input: its kind's settings without those its Ports step publishes the ports through
     * ({@link InstanceKindHandler#portSettings}), so the Ports step's rows are the one place a port is asked.
     */
    private static @NonNull InputScope workloadServiceScope(@Nullable Void none, @NonNull FormSpec declared,
                                                            @Nullable AccessContext access) {
        Set<String> owned = new LinkedHashSet<>();
        for (String kind : InstanceKinds.kindsWhere(handler -> handler.putOnlineReaches()
                .contains(PutOnlineReach.PORTS))) {
            owned.addAll(InstanceKinds.getHandler(kind).portSettings());
        }
        return InputScope.of(InstanceTemplateHandlers.replacing(declared, Map.of(InstanceModel.SETTINGS.getName(),
            Nested.of(InstanceModel.SETTINGS).schemaFrom(InstanceModel.KIND.getName())
                .omitting(owned.toArray(String[]::new)).build())));
    }

    /** A service template's request checks ({@link #serviceRefusal}), its chosen host ports read per declared port. */
    private static @Nullable DomainRefusal serviceAuthority(@NonNull Row template, PutOnline.@Nullable Service input,
                                                            @NonNull AccessContext access) {
        if (input == null) {
            return null;
        }
        List<PublishedPort> ports;
        try {
            ports = chosenPorts(template, input.ports());
        } catch (Violations unspelled) {
            return new DomainRefusal(ZenitRefusalReason.INVALID, "a chosen port is no port", PutOnline.PORTS,
                unspelled.all().getFirst().message());
        }
        return serviceRefusal(access, input.game_address(), input.serverId(), ports,
            port -> PutOnline.PORTS + "." + port.name());
    }

    /**
     * What both service flows refuse in the request, before anything exists: a game address given by anyone but an
     * operator or in no zone Hohenheim manages, and a chosen host port another owner holds on a chosen host (on a host
     * placement picks, the run's own claim refuses it the same way).
     *
     * @param fieldOf the form path a port's host port is chosen at
     */
    private static @Nullable DomainRefusal serviceRefusal(@NonNull AccessContext access, @Nullable String gameAddress,
                                                          @Nullable Integer serverId,
                                                          @NonNull List<PublishedPort> ports,
                                                          @NonNull Function<PublishedPort, String> fieldOf) {
        if (Texts.trimmedOrNull(gameAddress) != null && !HohenheimAccess.isAdmin(access)) {
            return new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "a game address is given by an operator");
        }
        Violations uncovered = GameAddresses.uncoveredRefusal(gameAddress);
        if (uncovered != null) {
            return new DomainRefusal(ZenitRefusalReason.INVALID, "no managed zone covers the game address",
                PutOnline.GAME_ADDRESS.getName(), uncovered.all().getFirst().message());
        }
        for (PublishedPort port : ports) {
            Violations taken = port.hostPort() == null || serverId == null ? null
                : PortPublications.takenRefusal(serverId, port.hostPort(), port.protocol());
            if (taken != null) {
                return new DomainRefusal(ZenitRefusalReason.INVALID, "port " + port.hostPort() + " is taken",
                    fieldOf.apply(port), taken.all().getFirst().message());
            }
        }
        return null;
    }

    /** @return the ports the template declares, each with the host port the input chose for it (null: open) */
    private static @NonNull List<PublishedPort> chosenPorts(@NonNull Row template, @NonNull Map<String, Object> given) {
        List<PublishedPort> ports = new ArrayList<>();
        for (PublishedPort port : PutOnline.declaredPorts(template)) {
            Object chosen = given.get(port.name());
            if (chosen == null || chosen.toString().isBlank()) {
                ports.add(port);
                continue;
            }
            ports.add(PublishedPort.parse(port.name(), chosen.toString().trim() + ":" + port.containerPort() + "/"
                + port.protocol()));
        }
        return ports;
    }

    /**
     * The ports a kind-led service publishes: its Ports step's rows (the purpose's token names one, or the given name
     * for Other), each mapped to the form path it is refused at.
     *
     * @throws DomainRefusal on the offending entry: an Other row without a name, a name given twice, a port out of
     *                       range
     */
    private static @NonNull Map<PublishedPort, String> servicePorts(PutOnline.@NonNull WorkloadService input) {
        Map<PublishedPort, String> ports = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < input.ports().size(); index++) {
            Map<String, Object> row = input.ports().get(index);
            String at = PutOnline.PORTS + "." + index + ".";
            PortPurpose purpose = PortPurpose.forName(RawValues.nonBlankString(
                row.get(PutOnline.PORT_PURPOSE.getName())));
            boolean named = purpose == PortPurpose.OTHER;
            String name = named ? RawValues.nonBlankString(row.get(PutOnline.PORT_NAME.getName())) : purpose.token();
            String nameField = at + (named ? PutOnline.PORT_NAME : PutOnline.PORT_PURPOSE).getName();
            if (name == null) {
                throw new DomainRefusal(ZenitRefusalReason.INVALID, "an Other port has no name", nameField,
                    HohenheimMicrocopy.VIOLATIONS.of("service_port_name_required"));
            }
            if (!names.add(name.trim())) {
                throw new DomainRefusal(ZenitRefusalReason.INVALID, "port " + name + " is named twice", nameField,
                    HohenheimMicrocopy.VIOLATIONS.of("service_port_name_taken").withArg("name", name.trim()));
            }
            Integer container = RawValues.parsedInt(row.get(PutOnline.PORT_CONTAINER.getName()));
            Integer host = RawValues.parsedInt(row.get(PutOnline.PORT_HOST.getName()));
            String outOfRange = !inPortRange(container) ? PutOnline.PORT_CONTAINER.getName()
                : host != null && !inPortRange(host) ? PutOnline.PORT_HOST.getName() : null;
            if (outOfRange != null) {
                throw new DomainRefusal(ZenitRefusalReason.INVALID, "port " + name + " is out of range",
                    at + outOfRange, HohenheimMicrocopy.VIOLATIONS.of("service_port_invalid").withArg("name", name));
            }
            ports.put(Objects.requireNonNull(PutOnline.rowPort(row), "a checked row publishes a port"),
                at + PutOnline.PORT_HOST.getName());
        }
        return ports;
    }

    private static boolean inPortRange(@Nullable Integer port) {
        return port != null && port >= 1 && port <= 65535;
    }

    /**
     * In the request, before a run is recorded: an operator, an address, and a kind that is put online on its own.
     */
    private static @Nullable DomainRefusal admitAddress(@Nullable Void none, PutOnline.@Nullable Address input,
                                                        @NonNull AccessContext access) {
        if (!HohenheimAccess.isAdmin(access)) {
            return new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "putting an address online is an operator act");
        }
        if (input == null) {
            // The offer: who may put an address online at all; the input is judged when it is submitted.
            return null;
        }
        if (Texts.trimmedOrNull(input.hostname()) == null) {
            return new DomainRefusal(ZenitRefusalReason.INVALID, "no address given", PutOnline.HOSTNAME.getName(),
                message("address_required"));
        }
        if (!PutOnline.offeredAsApp(input.upstream_kind())) {
            return new DomainRefusal(ZenitRefusalReason.INVALID, "kind " + input.upstream_kind() + " is no app",
                SiteModel.UPSTREAM_KIND.getName(), message("kind_not_offered"));
        }
        return httpsRefusal(input.hostname(), input.httpsAnswer(), access);
    }

    /** In the request, before a run is recorded: an operator, and a kind that is put online from its own settings. */
    private static @Nullable DomainRefusal admitWorkload(@Nullable Void none, PutOnline.@Nullable Workload input,
                                                         @NonNull AccessContext access) {
        DomainRefusal refusal = admitKind(access, input == null ? null : input.kind(), input != null
            && !PutOnline.offeredWith(input.kind(), PutOnlineReach.WEBSITE));
        return refusal != null || input == null ? refusal : httpsRefusal(input.hostname(), input.httpsAnswer(), access);
    }

    /**
     * In the request, before a run is recorded: an operator, a kind put online reached on its ports, its ports
     * ({@link #servicePorts}) and the service checks the template flow makes too ({@link #serviceRefusal}).
     */
    private static @Nullable DomainRefusal admitWorkloadService(@Nullable Void none,
                                                                PutOnline.@Nullable WorkloadService input,
                                                                @NonNull AccessContext access) {
        DomainRefusal refusal = admitKind(access, input == null ? null : input.kind(), input != null
            && !PutOnline.offeredWith(input.kind(), PutOnlineReach.PORTS));
        if (refusal != null || input == null) {
            return refusal;
        }
        Map<PublishedPort, String> ports;
        try {
            ports = servicePorts(input);
        } catch (DomainRefusal refused) {
            return refused;
        }
        return serviceRefusal(access, input.game_address(), input.serverId(), List.copyOf(ports.keySet()),
            ports::get);
    }

    /** In the request, before a run is recorded: an operator, and a kind that is put online as a machine. */
    private static @Nullable DomainRefusal admitMachine(@Nullable Void none, PutOnline.@Nullable Machine input,
                                                        @NonNull AccessContext access) {
        return admitKind(access, input == null ? null : input.kind(), input != null
            && !PutOnline.offeredWith(input.kind(), PutOnlineReach.NONE));
    }

    /** An operator's, and refused on the kind when this flow does not put that kind online. */
    private static @Nullable DomainRefusal admitKind(@NonNull AccessContext access, @Nullable String kind,
                                                     boolean notThisFlow) {
        if (!HohenheimAccess.isAdmin(access)) {
            return new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "a workload without a template is an operator's");
        }
        if (notThisFlow) {
            return new DomainRefusal(ZenitRefusalReason.INVALID, "kind " + kind + " is not put online this way",
                InstanceModel.KIND.getName(), message("kind_not_a_workload"));
        }
        return null;
    }

    /**
     * App, install, website, certificate, live.
     *
     * @return the new instance's id
     */
    private static @NonNull Integer putOnline(@NonNull OperationCall<Row, PutOnline.FromTemplate> call) {
        PutOnline.FromTemplate input = Objects.requireNonNull(call.input(), "put online has its input");
        Row template = call.subject();

        // 1. The app: the template's own create funnel (quota, placement, databases, variables).
        int instanceId = createApp(call, template, input.create());
        Row instance = Objects.requireNonNull(Models.get(InstanceModel.class).findById(instanceId), "the new app");
        call.attachSubject(InstanceOperations.INSTANCE, instance);
        call.reportStepDone(appDone(String.valueOf((Object) template.get(InstanceTemplateModel.NAME)), instance));

        // 2. Its install step, when the template declares one.
        installStep(call, template, instanceId);

        return goLive(call, instance, input.hostname(), input.httpsAnswer());
    }

    /**
     * Its install step, when the template declares one, as the system: it stamps pipeline-owned columns. A template
     * without one passes the step, and the run page says so instead of calling it done.
     */
    private static void installStep(@NonNull OperationCall<Row, ?> call, @NonNull Row template, int instanceId) {
        if (InstanceTemplates.hasInstallStep(template)) {
            ExecutionIdentity.runAsSystem("template-install", () -> new InstanceInstalls().install(instanceId));
            call.progress().reportProgressPart();
        } else {
            call.reportStepSkipped();
        }
    }

    /**
     * The app, through the template's own create funnel (quota, placement, databases, variables). After the request
     * its refusal (no host with room, a quota) is the run's words, never an unexplained error.
     *
     * @return the new instance's id
     */
    private static int createApp(@NonNull OperationCall<Row, ?> call, @NonNull Row template,
                                 InstanceTemplateOperations.@NonNull CreateFromTemplate create) {
        try {
            return new InstanceTemplates().createFromTemplate(template, create, call.access());
        } catch (Violations refused) {
            throw refusedAs("app", refused);
        }
    }

    /**
     * App, install, ports, game address, live.
     *
     * @return the new instance's id
     */
    private static @NonNull Integer putServiceOnline(@NonNull OperationCall<Row, PutOnline.Service> call) {
        PutOnline.Service input = Objects.requireNonNull(call.input(), "put online has its input");
        Row template = call.subject();
        int instanceId = createApp(call, template, input.create());
        Row instance = Objects.requireNonNull(Models.get(InstanceModel.class).findById(instanceId), "the new app");
        call.attachSubject(InstanceOperations.INSTANCE, instance);
        call.reportStepDone(appDone(String.valueOf((Object) template.get(InstanceTemplateModel.NAME)), instance));
        installStep(call, template, instanceId);
        publishPorts(call, instance, chosenPorts(template, input.ports()));
        gameAddressStep(call, instanceId, input.game_address());
        firstStart(call, instanceId, null, null);
        return instanceId;
    }

    /** The game address step: set through its one home ({@link GameAddresses#set}) when given, skipped when not. */
    private static void gameAddressStep(@NonNull OperationCall<?, ?> call, int instanceId, @Nullable String given) {
        String gameAddress = Texts.trimmedOrNull(given);
        if (gameAddress == null) {
            call.reportStepSkipped();
            return;
        }
        try {
            GameAddresses.set(instanceId, gameAddress);
        } catch (Violations refused) {
            throw refusedAs("game address", refused);
        }
        call.reportStepDone(done("game_address").withArg("address", gameAddress));
    }

    /**
     * Claims every declared port's host port on the instance's host through the ledger's one claim
     * ({@link PortPublications#reserve}) and writes the numbers into its settings, so its first deploy publishes
     * exactly those and the Overview and the game address read them.
     */
    private static void publishPorts(@NonNull OperationCall<?, ?> call, @NonNull Row instance,
                                     @NonNull List<PublishedPort> ports) {
        if (ports.isEmpty()) {
            call.reportStepSkipped();
            return;
        }
        int instanceId = instance.get(InstanceModel.ID);
        int serverId = ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID));
        List<PortPublication> wanted = new ArrayList<>();
        for (PublishedPort port : ports) {
            wanted.add(PortPublication.of(port));
        }
        List<PublishedPort> published = new ArrayList<>();
        try {
            List<PortPublication> claimed = PortPublications.reserve(instanceId, serverId, wanted);
            for (int index = 0; index < ports.size(); index++) {
                published.add(ports.get(index).withHostPort(
                    Objects.requireNonNull(claimed.get(index).preallocatedPort(), "a claimed port")));
            }
            Map<String, Object> settings = new LinkedHashMap<>();
            if (instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> stored) {
                stored.forEach((key, value) -> settings.put(String.valueOf(key), value));
            }
            settings.put(PublishedPort.FIELD, PublishedPort.toSettings(published));
            instance.set(InstanceModel.SETTINGS, settings);
            Models.get(InstanceModel.class).save(instance);
        } catch (Violations refused) {
            throw refusedAs("ports", refused);
        }
        List<String> hostPorts = new ArrayList<>();
        for (PublishedPort port : published) {
            hostPorts.add(port.hostPort() + "/" + port.protocol());
        }
        call.reportStepDone(done("ports").withArg("ports", String.join(", ", hostPorts))
            .withArg("count", hostPorts.size()));
    }

    /**
     * App, website, certificate, live.
     *
     * @return the new instance's id
     */
    private static @NonNull Integer putWorkloadOnline(@NonNull OperationCall<Void, PutOnline.Workload> call) {
        PutOnline.Workload input = Objects.requireNonNull(call.input(), "put online has its input");
        Row instance = createOfKind(call, input.name(), input.serverId(), input.kind(), input.settings(), null);
        return goLive(call, instance, input.hostname(), input.httpsAnswer());
    }

    /**
     * App, ports, game address, live.
     *
     * @return the new instance's id
     */
    private static @NonNull Integer putWorkloadServiceOnline(
            @NonNull OperationCall<Void, PutOnline.WorkloadService> call) {
        PutOnline.WorkloadService input = Objects.requireNonNull(call.input(), "put online has its input");
        List<PublishedPort> ports = List.copyOf(servicePorts(input).keySet());
        Row instance = createOfKind(call, input.name(), input.serverId(), input.kind(), input.settings(), null);
        int instanceId = instance.get(InstanceModel.ID);
        publishPorts(call, instance, ports);
        gameAddressStep(call, instanceId, input.game_address());
        firstStart(call, instanceId, null, null);
        return instanceId;
    }

    /**
     * App, live.
     *
     * @return the new instance's id
     */
    private static @NonNull Integer putMachineOnline(@NonNull OperationCall<Void, PutOnline.Machine> call) {
        PutOnline.Machine input = Objects.requireNonNull(call.input(), "put online has its input");
        // An ISO is for a machine created EMPTY: any other origin boots its own image and would never reach the
        // installer, so the pick is refused before anything exists.
        String media = Texts.trimmedOrNull(input.installMedia());
        if (media != null && ImageOrigin.of(input.settings() == null ? Map.of() : input.settings())
                != ImageOrigin.INSTALL_MEDIA) {
            throw refusedAs("app", Violations.ofField(InstanceOperations.INSTALL_MEDIA.getName(), media,
                HohenheimMicrocopy.VIOLATIONS.of("install_media_needs_empty_vm")));
        }
        Row instance = createOfKind(call, input.name(), input.serverId(), input.kind(), input.settings(),
            media == null ? null : placed -> new InstallMedia().requireOn(placed, media));
        int instanceId = instance.get(InstanceModel.ID);
        if (media != null) {
            // Before the first start: an absent workload takes the device row alone, and the deploy creates the VM
            // with the ISO in its drive, booted before the root disk.
            new InstanceDevices().attachInstallMedia(instanceId, media);
        }
        firstStart(call, instanceId, null, null);
        return instanceId;
    }

    /**
     * The app, as the template funnel writes one: placement chooses the host from the kind and its settings, and the
     * save meets every model hook (declarations, image policy, quota, capacity). The pipeline sealed each secret; the
     * record stores their text, so they are opened once here.
     *
     * @param onPlaced what the host placement chose must also hold, asked before the record is saved; null for none
     * @return the saved record, attached as the run's subject
     */
    private static @NonNull Row createOfKind(@NonNull OperationCall<Void, ?> call, @Nullable String givenName,
                                             @Nullable Integer serverId, @Nullable String kindId,
                                             @Nullable Map<String, Object> given, @Nullable IntConsumer onPlaced) {
        InstanceKindHandler kind = Objects.requireNonNull(InstanceKinds.getHandler(kindId), "admitted kind");
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = given == null ? new LinkedHashMap<>() : (Map<String, Object>) opened(given);
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        try {
            int placed = InstancePlacement.forActor(call.access(), serverId, InstancePlacement.Workload.of(kind,
                settings));
            if (onPlaced != null) {
                onPlaced.accept(placed);
            }
            String name = Texts.trimmedOrNull(givenName);
            if (name == null) {
                throw Violations.ofField(InstanceModel.NAME.getName(), givenName,
                    HohenheimMicrocopy.VIOLATIONS.of("name_required"));
            }
            instance.set(InstanceModel.NAME, name);
            instance.set(InstanceModel.KIND, kindId);
            instance.set(InstanceModel.SETTINGS, settings);
            instance.set(InstanceModel.SERVER_ID, placed);
            Models.get(InstanceModel.class).save(instance);
        } catch (Violations refused) {
            throw refusedAs("app", refused);
        }
        call.attachSubject(InstanceOperations.INSTANCE, instance);
        InstanceKindInfo info = InstanceKindRegistry.REGISTRY.get(Identifier.tryParse(String.valueOf(kindId)));
        call.reportStepDone(appDone(info == null ? String.valueOf(kindId) : info.getLabel(), instance));
        return instance;
    }

    /**
     * A new app's website and certificate when an address was given, each skipped when there is none to make, then
     * its first start.
     *
     * @return the app's id
     */
    private static @NonNull Integer goLive(@NonNull OperationCall<?, ?> call, @NonNull Row instance,
                                           @Nullable String address, PutOnline.@NonNull HttpsAnswer https) {
        int instanceId = instance.get(InstanceModel.ID);
        String hostname = Texts.trimmedOrNull(address);
        Microcopy note = null;
        if (hostname != null) {
            String name = String.valueOf((Object) instance.get(InstanceModel.NAME));
            createWebsite(name, hostname, InstanceUpstreamKind.ID.toString(), instanceId, Map.of(),
                https.accessListId());
            call.reportStepDone(done("website").withArg("address", hostname));
            note = certificate(hostname, https, call.subjectAccess());
            certificateStep(call, note, hostname, https);
        } else {
            call.reportStepSkipped();
            call.reportStepSkipped();
        }
        firstStart(call, instanceId, note, hostname);
        return instanceId;
    }

    /**
     * The first start, as the run's last step. A refusal (no admitted host, databases still provisioning) leaves the
     * app for its page: the step is left undone with why, so the run reads as a success that still owes its start;
     * an order that went out still waits for its certificate, so {@code note} says what follows.
     */
    private static void firstStart(@NonNull OperationCall<?, ?> call, int instanceId, @Nullable Microcopy note,
                                   @Nullable String hostname) {
        try {
            new InstanceService().deploy(instanceId, DeployTrigger.MANUAL);
        } catch (Violations | DomainRefusal notStarted) {
            Blast.log("PUT_ONLINE: first deploy of", instanceId, "did not start:", notStarted.getMessage());
            call.reportOutcome(message("not_started"));
            call.reportStepUndone(done("live_waiting"));
            return;
        }
        call.reportOutcome(note != null ? note : message(hostname == null ? "running" : "live")
            .withArg("address", hostname == null ? "" : hostname));
        call.reportStepDone(done("live"));
    }

    /**
     * Website, certificate.
     *
     * @return the new site's id
     */
    private static @NonNull Integer putAddressOnline(@NonNull OperationCall<Void, PutOnline.Address> call) {
        PutOnline.Address input = Objects.requireNonNull(call.input(), "put online has its input");
        String hostname = Objects.requireNonNull(Texts.trimmedOrNull(input.hostname()), "admitted with an address");
        int siteId = createWebsite(input.name(), hostname, Objects.requireNonNull(input.upstream_kind()), null,
            input.settings() == null ? Map.of() : input.settings(), input.httpsAnswer().accessListId());
        call.attachSubject(SiteOperations.SITE, Objects.requireNonNull(Models.get(SiteModel.class).findById(siteId)));
        call.reportStepDone(done("website").withArg("address", hostname));
        Microcopy note = certificate(hostname, input.httpsAnswer(), call.subjectAccess());
        call.reportOutcome(note != null ? note : message("live").withArg("address", hostname));
        certificateStep(call, note, hostname, input.httpsAnswer());
        return siteId;
    }

    /**
     * The certificate step: done when HTTPS was set up, its line saying how (ordered, the wildcard ordered, the
     * certificate already held, the upload stored); skipped when HTTPS waits ({@code note} says why).
     */
    private static void certificateStep(@NonNull OperationCall<?, ?> call, @Nullable Microcopy note,
                                        @NonNull String hostname, PutOnline.@NonNull HttpsAnswer https) {
        HttpsChoice choice = https.choice();
        Microcopy line = choice == null ? null : choice.doneLine();
        if (note != null || line == null) {
            call.reportStepSkipped();
            return;
        }
        String wildcard = wildcardOf(hostname);
        Row covering = usableCovering(hostname);
        String name = switch (choice) {
            case WILDCARD -> wildcard == null ? hostname : wildcard;
            case EXISTING -> covering == null ? hostname : String.valueOf((Object) covering.get(CertificateModel.NICE_NAME));
            case AUTOMATIC, UPLOAD, LATER -> hostname;
        };
        call.reportStepDone(line.withArg("address", hostname).withArg("name", name));
    }

    /** The site and its first address, atomically: a refused address (a claimed name) rolls the site back. */
    private static int createWebsite(@Nullable String name, @NonNull String hostname, @NonNull String upstreamKind,
                                     @Nullable Integer instanceId, @NonNull Map<String, Object> settings,
                                     @Nullable Integer accessListId) {
        SiteWrites.CreateInput create = new SiteWrites.CreateInput(name, hostname, upstreamKind, instanceId, settings,
            null, true, null, null, accessListId);
        int[] siteId = new int[1];
        try {
            Models.get(SiteModel.class).getResolvedDatasource()
                .withTransaction(transaction -> siteId[0] = SiteOperationHandlers.createSite(create));
        } catch (Violations refused) {
            throw refusedAs("website", refused);
        }
        return siteId[0];
    }

    /** After the request a refusal is the run's words: the claimed name, the name already a site's, the setting. */
    private static @NonNull DomainRefusal refusedAs(@NonNull String what, @NonNull Violations refused) {
        return new DomainRefusal(ZenitRefusalReason.INVALID, what + " refused: " + refused.getMessage(),
            refused.all().isEmpty() ? null : refused.all().getFirst().fieldName(),
            refused.all().isEmpty() ? null : refused.all().getFirst().message());
    }

    /** @return the tree with every {@link Secret} opened to its text, maps and lists copied */
    private static @Nullable Object opened(@Nullable Object value) {
        if (value instanceof Secret secret) {
            return secret.reveal();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, opened(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(PutOnlineHandlers::opened).toList();
        }
        return value;
    }

    /**
     * Sets the address's HTTPS up as its step chose: an order through the certificate operation's own order (its
     * reach pre-check and refusals included), HTTP-01 for the address or DNS-01 for its wildcard; the certificate
     * already held; or the upload, stored through the certificate form's own create (its key encrypted at rest).
     *
     * @return what is left to do, or null when HTTPS is set up
     */
    private static @Nullable Microcopy certificate(@NonNull String hostname, PutOnline.@NonNull HttpsAnswer answer,
                                                   @NonNull AccessContext access) {
        HttpsChoice choice = answer.choice();
        if (choice == null) {
            return message("https_later_note").withArg("address", hostname);
        }
        if (choice.ordersFromLetsEncrypt() && CertificateOperationHandlers.letsEncryptUnavailable() != null) {
            return message("https_off_note").withArg("address", hostname);
        }
        try {
            switch (choice) {
                case AUTOMATIC -> CertificateOperationHandlers.order(new CertificateOperations.Order(
                    List.of(hostname), null, null, CertificateModel.CHALLENGE_HTTP, null), access, null);
                case WILDCARD -> CertificateOperationHandlers.order(new CertificateOperations.Order(
                    List.of(Objects.requireNonNull(wildcardOf(hostname), "admitted with a parent domain")), null, null,
                    CertificateModel.CHALLENGE_DNS, CertificateModel.DNS_PUBLISHER_INTERNAL), access, null);
                case EXISTING -> {
                    if (usableCovering(hostname) == null) {
                        return message("https_refused_note").withArg("address", hostname);
                    }
                }
                case UPLOAD -> {
                    Map<String, Object> upload = new LinkedHashMap<>();
                    upload.put(CertificateModel.NICE_NAME.getName(), hostname);
                    upload.put(CertificateModel.CERTIFICATE_PEM.getName(), answer.certificatePem());
                    upload.put(CertificateModel.PRIVATE_KEY_PEM.getName(), revealed(answer.privateKeyPem()));
                    CertificateParts.create(upload);
                }
                case LATER -> {
                    return message("https_later_note").withArg("address", hostname);
                }
            }
            return null;
        } catch (Violations refused) {
            Blast.log("PUT_ONLINE: certificate for", hostname, "refused:", refused.getMessage());
            return message("https_refused_note").withArg("address", hostname);
        }
    }

    private static @NonNull Microcopy message(@NonNull String key) {
        return HohenheimMicrocopy.PUT_ONLINE.of("outcome_" + key);
    }

    /**
     * The line a finished step leaves on the run page: Microcopy resolved in each viewer's locale, the names it
     * carries (a template, a host, an address) plain arguments.
     */
    private static @NonNull Microcopy done(@NonNull String key) {
        return HohenheimMicrocopy.PUT_ONLINE.of("done_" + key);
    }

    /** @param what the template's name, or the kind's label for an app made from a kind's own settings */
    private static @NonNull Microcopy appDone(@NonNull Object what, @NonNull Row instance) {
        return done("app").withArg("what", what)
            .withArg("host", ServerModel.canonicalNameOf(instance.get(InstanceModel.SERVER_ID)));
    }
}
