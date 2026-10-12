package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.server.operation.InputScope;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceTemplateHandlers;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.async.ProgressSink;
import be.elevenways.protoblast.common.i18n.Microcopy;
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
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        OperationHandlers.attach(PutOnline.PUT_ADDRESS_ONLINE)
            .authorize(PutOnlineHandlers::admitAddress)
            .handle(PutOnlineHandlers::putAddressOnline);
        OperationHandlers.attach(PutOnline.PUT_WORKLOAD_ONLINE)
            .authorize(PutOnlineHandlers::admitWorkload)
            .handle(PutOnlineHandlers::putWorkloadOnline);
        OperationHandlers.attach(PutOnline.PUT_MACHINE_ONLINE)
            .inputScope(PutOnlineHandlers::kindHosts)
            .authorize(PutOnlineHandlers::admitMachine)
            .handle(PutOnlineHandlers::putMachineOnline);
    }

    private PutOnlineHandlers() {
    }

    static void init() {
    }

    /** Claiming an address is an operator act; anyone the template admits may put it online without one. */
    private static @Nullable DomainRefusal addressAuthority(@NonNull Row template,
            PutOnline.@Nullable FromTemplate input,
                                                           @NonNull AccessContext access) {
        if (input == null || Texts.trimmedOrNull(input.hostname()) == null || HohenheimAccess.isAdmin(access)) {
            return null;
        }
        return new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "an address is given by an operator");
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
        return null;
    }

    /** In the request, before a run is recorded: an operator, and a kind that is put online from its own settings. */
    private static @Nullable DomainRefusal admitWorkload(@Nullable Void none, PutOnline.@Nullable Workload input,
                                                         @NonNull AccessContext access) {
        return admitKind(access, input == null ? null : input.kind(), input != null
            && !PutOnline.offeredAsWorkload(input.kind()));
    }

    /** In the request, before a run is recorded: an operator, and a kind that is put online as a machine. */
    private static @Nullable DomainRefusal admitMachine(@Nullable Void none, PutOnline.@Nullable Machine input,
                                                        @NonNull AccessContext access) {
        return admitKind(access, input == null ? null : input.kind(), input != null
            && !PutOnline.offeredAsMachine(input.kind()));
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

    /** A kind-led input with its host pick narrowed to the hosts that can run the chosen kind. */
    private static @NonNull InputScope kindHosts(@Nullable Void none, @NonNull FormSpec declared,
                                                 @Nullable AccessContext access) {
        return InputScope.of(InstanceTemplateHandlers.replacing(declared, Map.of(
            InstanceTemplateOperations.SERVER_ID.getName(),
            InstanceParts.hostPick(InstanceTemplateOperations.SERVER_ID))));
    }

    /**
     * App, install, website, certificate, live.
     *
     * @return the new instance's id
     */
    private static @NonNull Integer putOnline(@NonNull OperationCall<Row, PutOnline.FromTemplate> call) {
        PutOnline.FromTemplate input = Objects.requireNonNull(call.input(), "put online has its input");
        Row template = call.subject();
        ProgressSink progress = call.progress();

        // 1. The app: the template's own create funnel (quota, placement, databases, variables).
        int instanceId = new InstanceTemplates().createFromTemplate(template, input.create(), call.access());
        Row instance = Objects.requireNonNull(Models.get(InstanceModel.class).findById(instanceId), "the new app");
        call.attachSubject(InstanceOperations.INSTANCE, instance);
        progress.reportProgressPart();

        // 2. Its install step, when the template declares one, as the system: it stamps pipeline-owned columns. A
        //    template without one passes the step, and the run page says so instead of calling it done.
        if (InstanceTemplates.hasInstallStep(template)) {
            ExecutionIdentity.runAsSystem("template-install", () -> new InstanceInstalls().install(instanceId));
            progress.reportProgressPart();
        } else {
            call.reportStepSkipped();
        }

        return goLive(call, instance, input.hostname(), input.https());
    }

    /**
     * App, website, certificate, live.
     *
     * @return the new instance's id
     */
    private static @NonNull Integer putWorkloadOnline(@NonNull OperationCall<Void, PutOnline.Workload> call) {
        PutOnline.Workload input = Objects.requireNonNull(call.input(), "put online has its input");
        Row instance = createOfKind(call, input.name(), input.serverId(), input.kind(), input.settings());
        return goLive(call, instance, input.hostname(), input.https());
    }

    /**
     * App, live.
     *
     * @return the new instance's id
     */
    private static @NonNull Integer putMachineOnline(@NonNull OperationCall<Void, PutOnline.Machine> call) {
        PutOnline.Machine input = Objects.requireNonNull(call.input(), "put online has its input");
        Row instance = createOfKind(call, input.name(), input.serverId(), input.kind(), input.settings());
        firstStart(call, instance.get(InstanceModel.ID), null, null);
        return instance.get(InstanceModel.ID);
    }

    /**
     * The app, as the template funnel writes one: placement chooses the host from the kind and its settings, and the
     * save meets every model hook (declarations, image policy, quota, capacity). The pipeline sealed each secret; the
     * record stores their text, so they are opened once here.
     *
     * @return the saved record, attached as the run's subject
     */
    private static @NonNull Row createOfKind(@NonNull OperationCall<Void, ?> call, @Nullable String givenName,
                                             @Nullable Integer serverId, @Nullable String kindId,
                                             @Nullable Map<String, Object> given) {
        InstanceKindHandler kind = Objects.requireNonNull(InstanceKinds.getHandler(kindId), "admitted kind");
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = given == null ? new LinkedHashMap<>() : (Map<String, Object>) opened(given);
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        try {
            int placed = InstancePlacement.forActor(call.access(), serverId, InstancePlacement.Workload.of(kind,
                settings));
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
        call.progress().reportProgressPart();
        return instance;
    }

    /**
     * A new app's website and certificate when an address was given, each skipped when there is none to make, then
     * its first start.
     *
     * @return the app's id
     */
    private static @NonNull Integer goLive(@NonNull OperationCall<?, ?> call, @NonNull Row instance,
                                           @Nullable String address, @Nullable String https) {
        int instanceId = instance.get(InstanceModel.ID);
        ProgressSink progress = call.progress();
        String hostname = Texts.trimmedOrNull(address);
        Microcopy note = null;
        if (hostname != null) {
            String name = String.valueOf((Object) instance.get(InstanceModel.NAME));
            createWebsite(name, hostname, InstanceUpstreamKind.ID.toString(), instanceId, Map.of());
            progress.reportProgressPart();
            note = certificate(hostname, https, call.subjectAccess());
            certificateStep(call, note);
        } else {
            call.reportStepSkipped();
            call.reportStepSkipped();
        }
        firstStart(call, instanceId, note, hostname);
        return instanceId;
    }

    /**
     * The first start, as the run's last step. A refusal (no admitted host, databases still provisioning) leaves the
     * app for its page; an order that went out still waits for its certificate, so {@code note} says what follows.
     */
    private static void firstStart(@NonNull OperationCall<?, ?> call, int instanceId, @Nullable Microcopy note,
                                   @Nullable String hostname) {
        try {
            new InstanceService().deploy(instanceId, DeployTrigger.MANUAL);
            call.reportOutcome(note != null ? note : message(hostname == null ? "running" : "live")
                .withArg("address", hostname == null ? "" : hostname));
        } catch (Violations | DomainRefusal notStarted) {
            Blast.log("PUT_ONLINE: first deploy of", instanceId, "did not start:", notStarted.getMessage());
            call.reportOutcome(message("not_started"));
        }
        call.progress().reportProgressPart();
    }

    /**
     * Website, certificate.
     *
     * @return the new site's id
     */
    private static @NonNull Integer putAddressOnline(@NonNull OperationCall<Void, PutOnline.Address> call) {
        PutOnline.Address input = Objects.requireNonNull(call.input(), "put online has its input");
        ProgressSink progress = call.progress();
        String hostname = Objects.requireNonNull(Texts.trimmedOrNull(input.hostname()), "admitted with an address");
        int siteId = createWebsite(input.name(), hostname, Objects.requireNonNull(input.upstream_kind()), null,
            input.settings() == null ? Map.of() : input.settings());
        call.attachSubject(SiteOperations.SITE, Objects.requireNonNull(Models.get(SiteModel.class).findById(siteId)));
        progress.reportProgressPart();
        Microcopy note = certificate(hostname, input.https(), call.subjectAccess());
        call.reportOutcome(note != null ? note : message("live").withArg("address", hostname));
        certificateStep(call, note);
        return siteId;
    }

    /** The certificate step: done when the order went out, skipped when HTTPS waits ({@code note} says why). */
    private static void certificateStep(@NonNull OperationCall<?, ?> call, @Nullable Microcopy note) {
        if (note == null) {
            call.progress().reportProgressPart();
        } else {
            call.reportStepSkipped();
        }
    }

    /** The site and its first address, atomically: a refused address (a claimed name) rolls the site back. */
    private static int createWebsite(@Nullable String name, @NonNull String hostname, @NonNull String upstreamKind,
                                     @Nullable Integer instanceId, @NonNull Map<String, Object> settings) {
        SiteWrites.CreateInput create = new SiteWrites.CreateInput(name, hostname, upstreamKind, instanceId, settings,
            null, true, null, null, null);
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
     * Orders the address's certificate when asked, through the certificate operation's own order (its reach pre-check
     * and refusals included).
     *
     * @return what is left to do, or null when HTTPS is set up (or was not asked for)
     */
    private static @Nullable Microcopy certificate(@NonNull String hostname, @Nullable String https,
                                                   @NonNull AccessContext access) {
        if (!PutOnline.HTTPS_AUTOMATIC.equals(https)) {
            return message("https_later_note").withArg("address", hostname);
        }
        Microcopy unavailable = CertificateOperationHandlers.letsEncryptUnavailable();
        if (unavailable != null) {
            return message("https_off_note").withArg("address", hostname);
        }
        try {
            CertificateOperationHandlers.order(new CertificateOperations.Order(List.of(hostname), null, null,
                CertificateModel.CHALLENGE_HTTP, null), access, null);
            return null;
        } catch (Violations refused) {
            Blast.log("PUT_ONLINE: certificate for", hostname, "refused:", refused.getMessage());
            return message("https_refused_note").withArg("address", hostname);
        }
    }

    private static @NonNull Microcopy message(@NonNull String key) {
        return HohenheimMicrocopy.PUT_ONLINE.of("outcome_" + key);
    }
}
