package be.elevenways.hohenheim.server.cms;


import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.host.HostState;
import be.elevenways.hohenheim.host.HostStatusCell;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.Computed;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteLocales;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Multi-server Docker host inventory. The implicit {@code local} host always
 * exists and cannot be renamed or removed (only its declared public addresses
 * and its posture are editable); remote hosts are reached over SSH.
 * The LIST and the Overview subpage read STORED host state (health columns + last
 * preflight); the only live daemon contacts are the explicit probe and preflight
 * actions, both of which persist their outcome. Rendering any page is side-effect-free.
 */
public final class ServerResource extends RowResource {

    // AIDEV-NOTE: SSH target must be a bare [user@]host[:port] that never starts with '-', so it
    // cannot be parsed as an ssh option (e.g. -oProxyCommand=...) when passed to the ssh argv.
    private static final Pattern SSH_TARGET = Pattern.compile(
        "^(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*@)?(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*|\\[[0-9A-Fa-f:]+\\])(?::[0-9]{1,5})?$");

    /**
     * The plain statement of what enrolling grants, at the point of enrolling. Driving a
     * remote Docker daemon IS root-equivalent access to that machine and no mechanism we
     * add changes that; the honest mitigation is that nobody pastes a target without
     * being told.
     *
     * AIDEV-NOTE: CREATE-only since the overview wave (2026-08-11). This was one of seven
     * Computed pseudo-fields faking a status panel on the EDIT form; the other six are
     * deleted (their data renders structured on {@link ServerOverviewPage}), but this one
     * is consent copy for the enrolment act itself, so it stays exactly where the target
     * gets pasted and nowhere else.
     */
    private static final StringField TRUST_NOTICE = StringField.builder("trust_notice")
        .label(HohenheimFormCopy.label("trust_notice"))
        .visibleIn(EditView.CREATE)
        .build();

    /**
     * Write-only: a pasted Incus trust token triggers {@link IncusTrust#enrollWithToken}
     * after save (through {@link HostEnrolment}, outside the save transaction) and is never
     * stored -- tokens are one-use and short-lived by design.
     */
    private static final StringField INCUS_TRUST_TOKEN = StringField.builder("incus_trust_token")
        .label(HohenheimFormCopy.label("incus_trust_token"))
        .help(HohenheimFormCopy.help("incus_trust_token"))
        .build();

    private final FormSpec formSpec = FormSpec.builder()
        .add(ServerModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(ServerModel.RUNTIME))
        .add(ServerModel.SSH_TARGET)
        .add(ServerModel.INCUS_URL)
        .add(INCUS_TRUST_TOKEN)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(ServerModel.POSTURE))
        .add(ServerModel.PUBLIC_IPV4)
        .add(ServerModel.PUBLIC_IPV6)
        .add(Computed.of(TRUST_NOTICE, values -> hostCopy(serverCopy(
                ServerModel.RUNTIME_INCUS.equals(values.get("runtime"))
                    ? "trust_notice_body_incus" : "trust_notice_body")))
            .dependsOn("runtime")
            .build())
        // Enrolling a host is naming it, saying how it is reached and trusting it. The
        // public addresses are a fact about the network the operator often fills in later.
        .section(FormSection.advanced(
            ServerModel.PUBLIC_IPV4.getName(),
            ServerModel.PUBLIC_IPV6.getName()))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // AIDEV-NOTE: the public address was stored and never rendered anywhere. It reads
        // under the name rather than as a column, because the SSH target -- which is what
        // an operator actually pastes into a terminal -- keeps the copy chip.
        .column(ColumnSpec.fromField(ServerModel.NAME).filterable().subtext("public_ipv4").build())
        .column(ColumnSpec.fromField(ServerModel.PUBLIC_IPV4).hidden().build())
        .column(ColumnSpec.fromField(ServerModel.RUNTIME).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.SSH_TARGET).filterable().copyable().build())
        .column(ColumnSpec.fromField(ServerModel.ADMISSION).filterable().build())
        .column(ColumnSpec.fromField(ServerModel.POSTURE).filterable().build())
        .column(ColumnSpec.virtual("host_status", Microcopy.of("host_status").withFilter("scope", "server"))
            .renderer("hohenheim:cms/cell/host-status").build())
        .filter(FilterSpec.forField(ServerModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ServerModel.NAME)).build())
        .filter(FilterSpec.forField(ServerModel.RUNTIME, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(ServerModel.RUNTIME)).build())
        .filter(FilterSpec.forField(ServerModel.ADMISSION, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(ServerModel.ADMISSION)).build())
        .filter(FilterSpec.forField(ServerModel.SSH_TARGET, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ServerModel.SSH_TARGET)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "server"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "server"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "server"); }
    @Override public @NonNull String slug() { return "servers"; }
    @Override public @NonNull Model model() { return Models.get(ServerModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    /** A host inventory is counted in handfuls: no saved views, no rule builder, no column gear. */
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** A host is hunted for by its name or by the address something else reported it at. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(ServerModel.NAME, ServerModel.SSH_TARGET, ServerModel.PUBLIC_IPV4, ServerModel.PUBLIC_IPV6);
    }

    // AIDEV-NOTE: the UNGROUPED top block, beside the dashboard -- not a labelled section.
    // A host is not something you deploy, it is what everything else is deployed ONTO, so
    // every labelled group it could join would be a lie about what it is. NavGroup.DEFAULT
    // renders unlabelled and never collapses, which is exactly the "always there" register
    // this and the dashboard need.
    @Override public @NonNull NavGroup navGroup() { return NavGroup.DEFAULT; }
    @Override public int navOrder() { return 10; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "server");
    }
    @Override public @NonNull Icon icon() { return Icon.of("server"); }

    /**
     * The host record's front door is the Overview page; the edit action keeps the form.
     * One declaration drives the tab order, the row title link and the create landing,
     * each derived panel-correctly by the framework.
     */
    @Override
    public @Nullable String landingSubpage() {
        return ServerOverviewPage.SLUG;
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>();
        pages.add(new ServerOverviewPage(this));
        pages.add(new ServerMediaPage());
        pages.addAll(this.frameworkSubpages());
        return pages;
    }

    /** mode is staged by persist/update but is not a form entry; stamp it here. */
    @Override
    public @NonNull Row valuesToRow(@NonNull Map<String, Object> coerced) {
        Row row = super.valuesToRow(coerced);
        if (coerced.get("mode") instanceof String mode) {
            row.set(ServerModel.MODE, mode);
        }
        return row;
    }

    @Override
    public void applyValuesToRow(@NonNull Row row, @NonNull Map<String, Object> coerced) {
        if (coerced.containsKey("name")) {
            row.set(ServerModel.NAME, (String) coerced.get("name"));
        }
        if (coerced.containsKey("runtime") && coerced.get("runtime") instanceof String runtime) {
            row.set(ServerModel.RUNTIME, runtime);
        }
        if (coerced.containsKey("ssh_target")) {
            row.set(ServerModel.SSH_TARGET, (String) coerced.get("ssh_target"));
        }
        if (coerced.containsKey("incus_url")) {
            Object url = coerced.get("incus_url");
            String value = url != null ? String.valueOf(url).trim() : "";
            row.set(ServerModel.INCUS_URL, value.isEmpty() ? null : value);
        }
        if (coerced.containsKey("posture") && coerced.get("posture") instanceof String posture) {
            row.set(ServerModel.POSTURE, posture);
        }
        if (coerced.get("mode") instanceof String mode) {
            row.set(ServerModel.MODE, mode);
        }
        applyAddressValues(row, coerced);
    }

    /** The declared public addresses; blank folds to null (the model hook validates). */
    private static void applyAddressValues(@NonNull Row row, @NonNull Map<String, Object> coerced) {
        if (coerced.containsKey("public_ipv4")) {
            Object value = coerced.get("public_ipv4");
            row.set(ServerModel.PUBLIC_IPV4, value != null ? String.valueOf(value) : null);
        }
        if (coerced.containsKey("public_ipv6")) {
            Object value = coerced.get("public_ipv6");
            row.set(ServerModel.PUBLIC_IPV6, value != null ? String.valueOf(value) : null);
        }
    }

    /**
     * STORED state per host as a structured cell: health columns and the last preflight,
     * never a live probe and never a flattened sentence.
     *
     * AIDEV-NOTE: QUARANTINE is asked FIRST and off its own column, because it was
     * invisible here. This branched on {@code last_error_kind} alone, and since M078 moved
     * the quarantine verdict to {@code quarantined_at}, a successful probe CLEARS the error
     * kind -- so a quarantined-but-reachable host rendered in the list with no quarantine
     * word anywhere. A security state a later success hides is worse than no state.
     * Static and package-reachable on purpose: {@link ServerOverviewPage} renders the SAME
     * cell in its state header, so the list and the overview can never disagree.
     */
    static @NonNull HostStatusCell statusCellOf(@NonNull Row row) {
        Instant lastSeen = row.get(ServerModel.LAST_SEEN_AT);
        String lastSeenIso = lastSeen != null ? lastSeen.toString() : null;
        String daemon = daemonLabelOf(row);
        RelativeTimeWording wording = defaultWording();
        if (row.get(ServerModel.QUARANTINED_AT) != null) {
            return new HostStatusCell(HostState.QUARANTINED, daemon, null, lastSeenIso, wording);
        }
        String errorKind = row.get(ServerModel.LAST_ERROR_KIND);
        if (errorKind != null && !errorKind.isBlank()) {
            return new HostStatusCell(HostState.ERROR, daemon, errorKind, lastSeenIso, wording);
        }
        if (lastSeen == null) {
            return new HostStatusCell(HostState.NEVER_PROBED, daemon, null, null, wording);
        }
        // A host whose last contact is older than the placement bound looks identical to a
        // healthy one otherwise: same version, same admission, no error kind. The refusal
        // an operator would otherwise only meet at deploy is stated where they read.
        if (lapsed(row)) {
            return new HostStatusCell(HostState.SILENT, daemon, null, lastSeenIso, wording);
        }
        return new HostStatusCell(HostState.OK, daemon, null, lastSeenIso, wording);
    }

    /** "Docker 27.1.1" / "Incus 7.3": the daemon label plus its STORED version. */
    private static @NonNull String daemonLabelOf(@NonNull Row row) {
        Object capabilities = row.get(ServerModel.CAPABILITIES);
        String label = ServerModel.isIncus(row) ? "Incus" : "Docker";
        String versionKey = ServerModel.isIncus(row) ? "incus_version" : "docker_version";
        return capabilities instanceof Map<?, ?> map
            && map.get(versionKey) instanceof String version && !version.isBlank()
            ? label + " " + version : label;
    }

    /** Server-default-locale relative-time wording; null when no runtime is booted. */
    private static @Nullable RelativeTimeWording defaultWording() {
        try {
            return RelativeTimeWording.resolve(
                LocaleChain.of(RouteLocales.get().getDefaultLocale()),
                Zenit.getMessageResolver());
        } catch (RuntimeException unbooted) {
            return null;
        }
    }

    /** Whether this host is past the declared contact bound, asked through the GATE itself. */
    private static boolean lapsed(@NonNull Row row) {
        try {
            HostAdmission.requireRecentContact(row);
            return false;
        } catch (Violations refused) {
            return true;
        }
    }

    /**
     * Host stats are computed without a requesting conduit, so they speak the
     * server's default locale.
     */
    static @NonNull String hostCopy(@NonNull Microcopy microcopy) {
        return microcopy.resolve(LocaleChain.of(RouteLocales.get().getDefaultLocale()),
            Zenit.getMessageResolver());
    }

    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if ("host_status".equals(column.name())) {
            return statusCellOf(row);
        }
        return super.cellValue(row, column);
    }

    // -- row actions (the ceremony and lifecycle verbs live in their own classes) ---

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.addAll(ServerTrustActions.actions());
        actions.addAll(ServerLifecycleActions.actions());
        return actions;
    }

    /**
     * A host-scoped catalog key.
     *
     * AIDEV-NOTE: the scope is stamped HERE, beside the key. {@link #hostCopy} used to add
     * it while resolving, and a filter added by a wrapper that takes an already-built
     * Microcopy is invisible to the Java key scan, so {@code DeclaredMicrocopyKeysTest}
     * judged host_summary and host_unknown_platform unfiltered and called two perfectly
     * good entries missing.
     */
    static @NonNull Microcopy serverCopy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "server");
    }

    /**
     * Enrolment reaches OUTSIDE this datasource (key tools, a TLS scan, a daemon spending a
     * one-use token), so the framework must not wrap it in its rollback transaction: the
     * write is two-phase and owned here -- see {@link #persistRow}.
     *
     * AIDEV-NOTE: the contract this flag carries ("refuse an out-of-scope caller before the
     * first write") is trivially kept: the resource is unscoped (the operator panel's own
     * permission is the gate) and every refusal -- validation, the https demand of a trust
     * token, the local host's immutable identity -- is raised before phase one writes.
     */
    @Override
    public boolean verifiesScopeBeforeMutating() {
        return true;
    }

    /**
     * The implicit local host's identity renders READ-ONLY on its edit form (and its trust
     * token not at all): only its declared public addresses and its posture are editable.
     *
     * AIDEV-NOTE: this replaced a local-host branch in updateRow that saved addresses and
     * posture and silently dropped every other submitted field while the form said "Saved".
     * The binding is what makes the form HONEST (the controls are disabled), the framework's
     * field-access enforcement strips a hand-crafted value, and {@link #refuseLocalIdentityEdit}
     * refuses one that reaches updateRow through any other lane -- never a silent drop.
     */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (String identity : LOCAL_IMMUTABLE) {
            bindings.add(ResourceFieldBinding.of(identity, FieldAccess.customRecordAware(
                (ctx, record) -> isLocal(record)
                    ? FieldAccess.Decision.READONLY : FieldAccess.Decision.EDITABLE)));
        }
        bindings.add(ResourceFieldBinding.of(INCUS_TRUST_TOKEN.getName(), FieldAccess.customRecordAware(
            (ctx, record) -> isLocal(record)
                ? FieldAccess.Decision.HIDDEN : FieldAccess.Decision.EDITABLE)));
        return bindings;
    }

    /** The local host's identity entries: everything its edit form shows but may not change. */
    private static final List<String> LOCAL_IMMUTABLE = List.of(
        ServerModel.NAME.getName(), ServerModel.RUNTIME.getName(),
        ServerModel.SSH_TARGET.getName(), ServerModel.INCUS_URL.getName());

    /** Whether a (possibly absent) record is the implicit local host. */
    private static boolean isLocal(@Nullable Object record) {
        return record instanceof Row row && ServerService.LOCAL.equals(row.get(ServerModel.NAME));
    }

    /**
     * Phase one commits the host row in its own short transaction; phase two
     * ({@link HostEnrolment#afterCreate}) mints its client identities and spends the trust
     * token outside any transaction, recording an incomplete ceremony ON the committed row.
     *
     * AIDEV-NOTE: the identity is still minted at once, so the very first thing the operator
     * sees on the record is the credential to install (authorized_keys, or incus config
     * trust) -- there is never a window in which the controller's ambient identity would do.
     * What changed is that a failing mint or enrolment no longer rolls the row back: a new
     * host is admission-blocked until preflight and admit anyway, so the committed row IS the
     * pending record, and erasing it is what orphaned a daemon-side trust entry and a spent
     * token.
     */
    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        String runtime = runtimeOf(values, null);
        validate(values, null);
        String token = takeTrustToken(values, null);
        // MODE is the DOCKER lane's transport discriminator; an incus host keeps the
        // default (its transport is declared by incus_url instead).
        values.put("mode", ServerModel.RUNTIME_DOCKER.equals(runtime)
            ? ServerService.MODE_SSH : ServerService.MODE_LOCAL);
        Object[] id = new Object[1];
        this.inMutationTransaction(() -> id[0] = super.persistRow(values, accessContext));
        reportIncomplete(HostEnrolment.afterCreate(id[0], token));
        ServerOptions.refresh();
        return id[0];
    }

    /**
     * Phase one saves the submitted fields in their own short transaction; phase two
     * ({@link HostEnrolment#afterUpdate}) spends a pasted trust token outside it.
     */
    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        // The implicit local host keeps its identity IMMUTABLE (name, target, mode,
        // runtime), but its declared public addresses AND its posture are legitimately
        // operator-set. Addresses: without that lane the one host every dev install
        // runs on could never carry an A record. Posture: instance placement refuses
        // trusted_only, so a host whose posture cannot be edited can never accept a
        // tenant workload -- on a single-machine install (the primary deployment
        // shape) the local row IS the compute host.
        if (isLocal(existing)) {
            refuseLocalIdentityEdit(existing, coerced);
            this.inMutationTransaction(() -> {
                applyAddressValues(existing, coerced);
                if (coerced.get("posture") instanceof String posture) {
                    existing.set(ServerModel.POSTURE, posture);
                }
                Models.get(ServerModel.class).save(existing);
            });
            ServerOptions.refresh();
            return;
        }
        Map<String, Object> values = CmsSupport.mutable(coerced);
        String runtime = runtimeOf(values, existing);
        validate(values, existing);
        String token = takeTrustToken(values, existing);
        values.put("mode", ServerModel.RUNTIME_DOCKER.equals(runtime)
            ? ServerService.MODE_SSH : ServerService.MODE_LOCAL);
        this.inMutationTransaction(() -> super.updateRow(existing, values, accessContext));
        reportIncomplete(HostEnrolment.afterUpdate(existing.get(ServerModel.ID), token));
        ServerOptions.refresh();
    }

    /**
     * Refuse a write that would change the local host's identity, naming the field.
     *
     * @throws Violations {@code local_server_immutable} on the first changed identity entry
     */
    private static void refuseLocalIdentityEdit(@NonNull Row existing, @NonNull Map<String, Object> coerced) {
        for (String identity : LOCAL_IMMUTABLE) {
            if (!coerced.containsKey(identity)) {
                continue;
            }
            String submitted = Texts.trimmedOrNull(coerced.get(identity));
            String stored = Texts.trimmedOrNull(existing.get(identity));
            if (!Objects.equals(submitted, stored)) {
                throw Violations.ofField(identity, coerced.get(identity),
                    CmsSupport.violationText("local_server_immutable"));
            }
        }
        String token = INCUS_TRUST_TOKEN.getName();
        if (Texts.trimmedOrNull(coerced.get(token)) != null) {
            throw Violations.ofField(token, "", CmsSupport.violationText("local_server_immutable"));
        }
    }

    /**
     * Tell the operator, on the page this save lands on, that the host is saved but not
     * enrolled; the durable half of the same fact is the error HostEnrolment recorded on the
     * row, which the Overview and the list render.
     */
    private static void reportIncomplete(HostEnrolment.@NonNull Outcome outcome) {
        Microcopy failure = outcome.failure();
        Conduit conduit = RouteScope.currentConduit();
        if (failure != null && conduit != null) {
            HohenheimFlash.warning(conduit, failure);
        }
    }

    /** The submitted (or stored) runtime this write is about. */
    private static @NonNull String runtimeOf(@NonNull Map<String, Object> values,
                                             @Nullable Row existing) {
        Object submitted = values.get("runtime");
        if (submitted instanceof String runtime && !runtime.isBlank()) {
            return runtime;
        }
        return existing != null ? ServerModel.runtimeOf(existing) : ServerModel.RUNTIME_DOCKER;
    }

    /**
     * Pull the one-shot trust token OUT of the values so it is never persisted, refusing it
     * up front -- before phase one writes anything -- when the host it would enrol on is not
     * an Incus host reached over https.
     *
     * @throws Violations {@code incus_token_needs_https} on the token field
     */
    private static @Nullable String takeTrustToken(@NonNull Map<String, Object> values,
                                                   @Nullable Row existing) {
        String token = Texts.trimmedOrNull(values.remove(INCUS_TRUST_TOKEN.getName()));
        if (token == null) {
            return null;
        }
        String url = CmsSupport.textOf(values, existing, ServerModel.INCUS_URL);
        if (!ServerModel.RUNTIME_INCUS.equals(runtimeOf(values, existing)) || !url.startsWith("https://")) {
            throw Violations.ofField(INCUS_TRUST_TOKEN.getName(), "",
                CmsSupport.violationText("incus_token_needs_https"));
        }
        return token;
    }

    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        if (ServerService.LOCAL.equals(existing.get(ServerModel.NAME))) {
            throw Violations.ofForm(CmsSupport.violationText("local_server_undeletable"));
        }
        super.deleteRow(existing, accessContext);
        ServerOptions.refresh();
    }

    /**
     * Why this host's delete is offered but dead: the local host is the machine Hohenheim
     * itself runs on, and a host still carrying workloads would leave every instance,
     * stack and database row naming a host that no longer exists.
     *
     * AIDEV-NOTE: the local refusal was already enforced in {@link #deleteRow}, so the
     * button was offered on every surface and always failed; declaring it here is what
     * renders it dead WITH the reason, and the same resolver refuses the direct POST. The
     * workload refusal is new and is the {@code ProjectGuards} policy applied one tier
     * down: a project cannot be deleted while it owns records either.
     */
    @Override
    public @Nullable Microcopy deleteUnavailableReason(@NonNull Row record,
                                                       @NonNull AccessContext accessContext) {
        if (ServerService.LOCAL.equals(record.get(ServerModel.NAME))) {
            return Microcopy.of("delete_local").withFilter("scope", "server");
        }
        Integer id = record.get(ServerModel.ID);
        Row migrating = id == null ? null : ServerModel.migratingOnto(id).first();
        if (migrating != null) {
            // Mid-flight the record still names its SOURCE host, so the workload count
            // below never sees the destination; the funnel refuses this by name too.
            return Microcopy.of("delete_migrating").withFilter("scope", "server")
                .withArg("instance", String.valueOf((Object) migrating.get(InstanceModel.NAME)));
        }
        long workloads = workloadsOn(id);
        if (workloads > 0) {
            return Microcopy.of("delete_in_use").withFilter("scope", "server")
                .withArg("workloads", workloads);
        }
        return super.deleteUnavailableReason(record, accessContext);
    }

    /**
     * The dialog says what the record actually is -- an inventory entry -- because
     * removing it does not touch the machine or anything running on it.
     */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmation() {
        return deleteConfirmation(Microcopy.of("delete_confirm").withFilter("scope", "server"));
    }

    /** @return how many stored workloads still name this host */
    private static long workloadsOn(@Nullable Integer serverId) {
        if (serverId == null) {
            return 0;
        }
        return Models.get(InstanceModel.class).find()
                .where(InstanceModel.SERVER_ID.eq(serverId))
                .where(InstanceModel.DELETED_AT.isNull())
                .count()
            + Models.get(StackModel.class).find()
                .where(StackModel.SERVER_ID.eq(serverId)).count()
            + Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.SERVER_ID.eq(serverId)).count();
    }

    /**
     * Name spelling plus the runtime's own address demand.
     *
     * AIDEV-NOTE: every read takes the STORED value when the write does not carry the key.
     * The inline cell lane hands updateRow a map holding EXACTLY ONE entry, and ssh_target
     * was read straight off it: renaming any Docker-runtime host refused with
     * "ssh_target_format", naming an address the operator never touched.
     */
    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        String name = CmsSupport.textOf(coerced, existing, ServerModel.NAME);
        if (ServerService.LOCAL.equals(name)) {
            throw Violations.ofField("name", name, CmsSupport.violationText(
                existing == null ? "local_server_reserved" : "local_server_immutable"));
        }
        if (name.isEmpty() || !name.matches("[a-z0-9][a-z0-9-]*")) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_format"));
        }
        String target = CmsSupport.textOf(coerced, existing, ServerModel.SSH_TARGET);
        if (ServerModel.RUNTIME_INCUS.equals(runtimeOf(coerced, existing))) {
            String url = CmsSupport.textOf(coerced, existing, ServerModel.INCUS_URL);
            try {
                IncusEndpoint.parse(url);
            } catch (IllegalArgumentException bad) {
                throw Violations.ofField("incus_url", url,
                    CmsSupport.violationText("incus_url_format"));
            }
            // The admin lane is OPTIONAL on an Incus host (the daemon is driven over
            // https), but a declared one is held to the same spelling as anywhere else:
            // a target that could be read as an ssh option must never reach an argv.
            if (!target.isEmpty() && !SSH_TARGET.matcher(target).matches()) {
                throw Violations.ofField("ssh_target", target,
                    CmsSupport.violationText("ssh_target_format"));
            }
            return;
        }
        if (target.isEmpty() || !SSH_TARGET.matcher(target).matches()) {
            throw Violations.ofField("ssh_target", target, CmsSupport.violationText("ssh_target_format"));
        }
    }

    /**
     * What the reconciler found on these hosts, demoted out of the sidebar.
     */
    @Override
    public @NonNull List<RelatedPage> relatedPages() {
        return List.of(RelatedPage.toPeer("reconcile-findings"));
    }

}
