package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.cluster.JoinCodeShown;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.LinkedServerCodeModel;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ListScope;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.StorePages;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.live.LiveFeed;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SecretResult;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.security.SecretDisclosures;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Servers list: this server first, then every Hohenheim it is linked to, with Let another Hohenheim manage this
 * server and Add a Hohenheim server as buttons, Check now and Remove on each row, and the card saying who manages
 * this server; a row opens the server's own page (LinkedServerPage).
 *
 * AIDEV-NOTE: a typed store, never a row list: its first row is this server, which no table holds (precedent
 * AppParts). Its columns are the model's own fields (labels, empty-cell words), the name's headed by the record's
 * noun as the board heads it; only the line under the name and the state are the list's own. Apps and Sites are each
 * server's own counts as it last told them (InstallationCounts), this server's read now, never counted from what this
 * server keeps of another. Every action is a placed operation over LinkedServers; the join code is answered once, as
 * a SecretResult the placement parks for the next render of this list alone (core's SecretDisclosures), whose card
 * shows it through plumage's one-time reveal; it is never stored. Administrators only (HohenheimSources.OPERATOR).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class LinkedServerParts {

    /** The key of this server's own row; no linked server's id spells it. */
    static final String OWN_KEY = "this";

    /** What the join code just issued is parked under, for the one next render of this list by the same session. */
    private static final String JOIN_CODE_SCOPE = "hohenheim.linked_servers|join_code";

    /** Why the link operations are kept from assistants. */
    private static final String NOT_FOR_ASSISTANTS = "linking servers is an administrator's act on the panel; an"
        + " assistant reads linked servers only once the master's record exists";

    /** The record's noun: the list's label for one server, and the head of the name's column. */
    private static final Microcopy SERVER = HohenheimMicrocopy.LINKED_SERVER.of("linked_server_singular");

    /** The hidden column whose words stand as muted text under a row's name: what that server is to this one. */
    private static final String NOTE_COLUMN = "note";

    /** The drawn column no field carries: the state badge with why the calls fail. */
    private static final String STATE_COLUMN = "state";

    /**
     * One row of the Servers list.
     *
     * @param key        the linked server's id, or {@link #OWN_KEY} for this server
     * @param name       what the row is called
     * @param note       the muted line under the name, null for none
     * @param state      the state cell
     * @param version    the version it runs, null when it never answered
     * @param lastAnswer when it last answered, null for this server and one never answering
     * @param apps       how many apps that server counts, as it last said; null before it said
     * @param sites      how many sites that server holds, as it last said; null before it said
     */
    public record ServerRow(@NonNull String key, @NonNull String name, @Nullable Microcopy note,
                            @NonNull StateLineCell state, @Nullable String version, @Nullable Instant lastAnswer,
                            @Nullable Integer apps, @Nullable Integer sites) {

        /** @return whether this is this server's own row */
        public boolean own() {
            return OWN_KEY.equals(this.key);
        }
    }

    static final SubjectType<ServerRow> SUBJECT = SubjectType.of(HohenheimIds.id("linked_server_row"),
        ServerRow.class, ServerRow::key);

    private static final FormSpec ADD_FORM = FormSpec.builder()
        .add(StringField.builder("name").required()
            .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_name")).build())
        .add(StringField.builder("address").required().placeholder("https://")
            .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_address")).build())
        .add(StringField.builder("join_code").required()
            .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_join_code"))
            .help(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_join_code_help")).build())
        .build();

    /** The add form, one component per form entry as the operation boot check requires. */
    public record AddInput(@Nullable String name, @Nullable String address, @Nullable String join_code) {
    }

    /** Links another Hohenheim as one this server manages, with the join code that server issued. */
    public static final Operation<Void, AddInput, String> ADD = Operation.declare(
            HohenheimIds.id("linked_server_add"))
        .happened(OperationSentences.of("linked_server_add"))
        .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_add"))
        .icon(Icon.of("plus"))
        .noSubject()
        .notForAssistants(NOT_FOR_ASSISTANTS)
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.of(ADD_FORM, AddInput.class, values -> new AddInput(
            values.get("name") instanceof String name ? name : null,
            values.get("address") instanceof String address ? address : null,
            values.get("join_code") instanceof String code ? code : null)))
        .result(String.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Issues a join code another Hohenheim adds this server with, answered once. */
    public static final Operation<Void, Void, SecretResult<UUID>> OFFER_JOIN = Operation.declare(
            HohenheimIds.id("linked_server_offer_join"))
        .happened(OperationSentences.of("linked_server_offer_join"))
        .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_offer_join"))
        .icon(Icon.of("key"))
        .noSubject()
        .notForAssistants("it answers a join code once, and an assistant is never sent a secret")
        .gate(HohenheimSources.OPERATOR)
        .result(SecretResult.<UUID>type())
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Asks a linked server who answers at its address now. */
    public static final Operation<ServerRow, Void, String> CHECK = Operation.declare(
            HohenheimIds.id("linked_server_check"))
        .happened(OperationSentences.of("linked_server_check"))
        .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_check"))
        .icon(Icon.of("rotate"))
        .one(SUBJECT)
        .notForAssistants(NOT_FOR_ASSISTANTS)
        .gate(HohenheimSources.OPERATOR)
        .result(String.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Ends a link: the other server is asked to forget it, and this one forgets it whatever it answers. */
    public static final Operation<ServerRow, Void, String> REMOVE = Operation.declare(
            HohenheimIds.id("linked_server_remove"))
        .happened(OperationSentences.of("linked_server_remove"))
        .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_remove"))
        .icon(Icon.of("link-slash"))
        .one(SUBJECT)
        .notForAssistants(NOT_FOR_ASSISTANTS)
        .gate(HohenheimSources.OPERATOR)
        .result(String.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    static {
        OperationHandlers.loader(SUBJECT, LinkedServerParts::load);
        OperationHandlers.attach(ADD)
            .availability((none, access) -> LinkedServers.whyCannotManage())
            .handle(call -> {
                AddInput input = Objects.requireNonNull(call.input(), "the add form is the input");
                Row added = LinkedServers.add(input.name(), input.address(), input.join_code());
                return String.valueOf((Object) added.get(LinkedServerModel.NAME));
            });
        OperationHandlers.attach(OFFER_JOIN)
            .availability((none, access) -> LinkedServers.whyCannotOffer())
            .handle(call -> {
                AccessContext access = call.access();
                UUID owner = access == null ? null : access.accountId();
                if (owner == null) {
                    throw new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "a join code is issued to an account");
                }
                LinkedServers.IssuedCode issued = LinkedServers.offerJoin(owner);
                return new SecretResult<>(issued.id(), Secret.of(issued.shown()));
            });
        OperationHandlers.attach(CHECK)
            .applies(row -> !row.own())
            .handle(call -> {
                Row checked = LinkedServers.check(linked(call.subject()));
                return LinkState.of(checked, LinkedServers.ownVersion()).token();
            });
        OperationHandlers.attach(REMOVE)
            .applies(row -> !row.own())
            .handle(call -> LinkedServers.remove(linked(call.subject())).token());
    }

    private LinkedServerParts() {
    }

    /** @return the Servers list of the operator panel */
    public static @NonNull PanelResource<ServerRow> admin() {
        TableSpec<ServerRow> table = TableSpec.<ServerRow>builder()
            .column(ColumnSpec.fromField(LinkedServerModel.NAME).label(SERVER).subtext(NOTE_COLUMN).build())
            .column(ColumnSpec.virtual(NOTE_COLUMN, HohenheimMicrocopy.LINKED_SERVER.of("linked_server_note"))
                .hidden().build())
            .column(ColumnSpec.virtual(STATE_COLUMN, HohenheimMicrocopy.LINKED_SERVER.of("linked_server_state"))
                .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build())
            .column(ColumnSpec.fromField(LinkedServerModel.BUILD).build())
            .column(ColumnSpec.fromField(LinkedServerModel.LAST_SEEN_AT).build())
            .column(ColumnSpec.fromField(LinkedServerModel.APPS).build())
            .column(ColumnSpec.fromField(LinkedServerModel.SITES).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("linked_server"), HohenheimSlugs.LINKED_SERVERS, SUBJECT)
            // The cluster's own name: a record's trail then names the cluster and its landing once (Servers / Kuifje).
            .label(HohenheimMicrocopy.NAV_CLUSTER.of("servers"))
            .recordLabel(SERVER)
            .icon(Icon.of("server"))
            .navGroup(NavGroup.DEFAULT)
            .navOrder(41)
            .list(ResourceList.store(table, StorePages.inMemory(table, access -> rows(),
                    (row, column) -> values(row).get(column.name())),
                    LiveFeed.of(AccessContext::isAccount, LinkedServerModel.MODEL_ID))
                .chrome(ListChrome.MINIMAL)
                .rowLinkToTab(RecordOverview.SLUG)
                .widgetsBelow(LinkedServerParts::joinCard)
                .build())
            .reads(ResourceReads.<ServerRow>typed(ServerRow::key)
                .load((key, access) -> load(key))
                .values(LinkedServerParts::values)
                .build()
                .title(row -> Microcopy.literalOrNull(row.name())))
            .tabs(ResourceTabs.of(List.of(LinkedServerPage.overview(), new LinkedServerPage.Remembered())))
            .actions(List.of(offerJoinAction(), addAction(), checkAction(), removeAction()))
            .build();
    }

    private static @NonNull PanelAction<ServerRow> addAction() {
        return PanelAction.<ServerRow, String>places(ADD, ActionPlacement.HEADER,
                (request, name) -> CmsActionResult.refreshWithToast(
                    HohenheimMicrocopy.LINKED_SERVER.of("linked_server_added").withArg("name", name)))
            .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_add"))
            .icon(Icon.of("plus"))
            .style(ActionStyle.PRIMARY)
            .inlineInHeader(true)
            .confirmation(ConfirmationSpec.builder().title(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_add"))
                .confirmLabel(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_add_confirm"))
                .style(ActionStyle.PRIMARY).build())
            .build();
    }

    /**
     * A button beside Add a Hohenheim server, unavailable with its reason on a server that has a master or manages
     * others. The code is parked for the list's next render, which shows it once in the card under the list; without a
     * session to park it with, the one-shot toast carrier stays the fallback, so the administrator still learns it.
     */
    private static @NonNull PanelAction<ServerRow> offerJoinAction() {
        return PanelAction.<ServerRow, SecretResult<UUID>>places(OFFER_JOIN, ActionPlacement.HEADER,
                (request, issued) -> {
                    Conduit conduit = request.request().conduit();
                    if (conduit != null && SecretDisclosures.park(conduit, JOIN_CODE_SCOPE, issued.secret())) {
                        return CmsActionResult.refresh();
                    }
                    Instant until = LinkedServers.codeExpiry(issued.key());
                    return CmsActionResult.refreshWithToast(HohenheimMicrocopy.LINKED_SERVER
                            .of("linked_server_code_issued").withArg("until", until == null ? Instant.EPOCH : until))
                        .withSecretArg("code", issued.secret().reveal());
                })
            .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_offer_join"))
            .icon(Icon.of("key"))
            .inlineInHeader(true)
            .description(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_offer_join_help"))
            .build();
    }

    private static @NonNull PanelAction<ServerRow> checkAction() {
        return PanelAction.<ServerRow, String>places(CHECK, ActionPlacement.ROW,
                (request, state) -> CmsActionResult.refreshWithToast(
                    HohenheimMicrocopy.LINKED_SERVER.of("linked_server_checked")))
            .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_check"))
            .icon(Icon.of("rotate"))
            .hiddenWhen(ServerRow::own)
            .build();
    }

    private static @NonNull PanelAction<ServerRow> removeAction() {
        return PanelAction.<ServerRow, String>places(REMOVE, ActionPlacement.ROW,
                (request, outcome) -> {
                    // A token this build does not declare is said as the cautious outcome: the link may stand there.
                    LinkedServers.Removal removal = Objects.requireNonNullElse(
                        LinkedServers.Removal.byToken(outcome), LinkedServers.Removal.KEPT_THERE);
                    return CmsActionResult.refreshWithToast(removal.message(request.subject().name()));
                })
            .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_remove"))
            .icon(Icon.of("link-slash"))
            .style(ActionStyle.DESTRUCTIVE)
            .hiddenWhen(ServerRow::own)
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_remove"),
                HohenheimMicrocopy.LINKED_SERVER.of("linked_server_remove_confirm"), ActionStyle.DESTRUCTIVE))
            .build();
    }

    /** @return this server's row, then every linked server's */
    static @NonNull List<ServerRow> rows() {
        String version = LinkedServers.ownVersion();
        List<ServerRow> rows = new ArrayList<>();
        rows.add(ownRow(version));
        for (Row linked : LinkedServers.all()) {
            rows.add(rowOf(linked, version));
        }
        return rows;
    }

    /** @return this server's own row, its counts read as it tells them to the servers it is linked to */
    private static @NonNull ServerRow ownRow(@NonNull String version) {
        InstallationCounts counts = InstallationCounts.read();
        return new ServerRow(OWN_KEY, LinkedServers.ownName(),
            HohenheimMicrocopy.LINKED_SERVER.of("linked_server_this_server"), LinkState.ownCell(), version, null,
            counts.apps(), counts.sites());
    }

    private static @NonNull ServerRow rowOf(@NonNull Row linked, @NonNull String version) {
        return new ServerRow(String.valueOf((Object) linked.get(LinkedServerModel.ID)),
            String.valueOf((Object) linked.get(LinkedServerModel.NAME)),
            LinkedServers.roleOf(linked) == LinkRole.MANAGED_BY
                ? HohenheimMicrocopy.LINKED_SERVER.of("linked_server_manages_this") : null,
            LinkState.cell(linked, version), linked.get(LinkedServerModel.BUILD),
            linked.get(LinkedServerModel.LAST_SEEN_AT), linked.get(LinkedServerModel.APPS),
            linked.get(LinkedServerModel.SITES));
    }

    /** @return the row of this key, null for no such server */
    static @Nullable ServerRow load(@NonNull String key) {
        if (OWN_KEY.equals(key)) {
            return ownRow(LinkedServers.ownVersion());
        }
        UUID id;
        try {
            id = UUID.fromString(key);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        Row linked = LinkedServers.byId(id);
        return linked == null ? null : rowOf(linked, LinkedServers.ownVersion());
    }

    /** @return the linked server a row names, read again */
    private static @NonNull Row linked(@NonNull ServerRow row) {
        Row linked = row.own() ? null : LinkedServers.byId(UUID.fromString(row.key()));
        if (linked == null) {
            throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "no such linked server");
        }
        return linked;
    }

    private static @NonNull Map<String, Object> values(@NonNull ServerRow row) {
        Map<String, Object> values = new HashMap<>();
        values.put(LinkedServerModel.NAME.getName(), row.name());
        values.put(NOTE_COLUMN, row.note());
        values.put(STATE_COLUMN, row.state());
        values.put(LinkedServerModel.BUILD.getName(), row.version());
        values.put(LinkedServerModel.LAST_SEEN_AT.getName(), row.lastAnswer());
        values.put(LinkedServerModel.APPS.getName(), row.apps());
        values.put(LinkedServerModel.SITES.getName(), row.sites());
        return values;
    }

    /**
     * The card under the list, on a server that can get a master or has one: who manages it since when, the join code
     * just issued (shown this once), or the one the viewer has waiting.
     */
    private static @NonNull WidgetTree joinCard(@NonNull ListScope scope) {
        Microcopy title = HohenheimMicrocopy.LINKED_SERVER.of("linked_server_offer_join");
        Row master = LinkedServers.master();
        if (master != null) {
            Instant since = Objects.requireNonNullElse(master.get(LinkedServerModel.CREATED_AT), Instant.EPOCH);
            return card(title, HohenheimMicrocopy.LINKED_SERVER.of("linked_server_managed_from")
                .withArg("name", String.valueOf((Object) master.get(LinkedServerModel.NAME))).withArg("since", since));
        }
        if (LinkedServers.managesAny()) {
            return WidgetTree.empty();
        }
        AccessContext access = scope.accessContext();
        UUID viewer = access.accountId();
        Row waiting = viewer == null ? null : LinkedServers.waitingCode(viewer);
        Conduit conduit = access.conduit();
        Secret issued = conduit == null ? null : SecretDisclosures.claim(conduit, JOIN_CODE_SCOPE);
        if (issued != null && waiting != null) {
            WidgetInstance shown = new WidgetInstance(HohenheimWidgets.JOIN_CODE.id(), Map.of())
                .withData(new JoinCodeShown(issued.reveal(), waiting.get(LinkedServerCodeModel.EXPIRES_AT)));
            WidgetInstance card = CardWidget.of(title, new WidgetTree(List.of(shown)));
            return new WidgetTree(List.of(AdminDashboard.section(card)));
        }
        return card(title, waiting == null ? HohenheimMicrocopy.LINKED_SERVER.of("linked_server_not_managed")
            : HohenheimMicrocopy.LINKED_SERVER.of("linked_server_code_waiting")
                .withArg("until", waiting.get(LinkedServerCodeModel.EXPIRES_AT)));
    }

    private static @NonNull WidgetTree card(@NonNull Microcopy title, @NonNull Microcopy lead) {
        WidgetInstance card = new WidgetInstance(CardWidget.ID, Map.of("title", title, "lead", lead),
            new WidgetTree(List.of()));
        return new WidgetTree(List.of(AdminDashboard.section(card)));
    }
}
