package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.cluster.RememberedKind;
import be.elevenways.hohenheim.cluster.RememberedName;
import be.elevenways.hohenheim.cluster.RememberedNames;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.cluster.ClusterRecords;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelCluster;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelNav;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.peer.SharedModels;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ExternalUrls;
import be.elevenways.zenit.server.peer.PeerRecords;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import be.elevenways.zenit.widget.common.builtin.TextBlockWidget;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A server's own page, opened from its row of the Servers list: its facts, and for a server this one manages what
 * this server remembers of it, per kind a count opening the remembered names; the cards stand one under the other,
 * facts first, and how changes arrive stands under them in muted words.
 *
 * AIDEV-NOTE: everything here is read from this server's own record (the linked server's row, core's PeerRecords
 * counts and pages of key, code and title), so the page reads the same while that server is silent. A kept record's
 * document is never read or shown. A kind is named by its model's own noun, in the plural, and the kinds stand in the
 * order the panel's sidebar declares their entries.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class LinkedServerPage {

    /** How many remembered names one page lists. */
    static final int NAMES_PER_PAGE = 50;

    /** The band under the cards: the grid's full width (AdminDashboard's band), its words muted. */
    private static final String NOTE_BAND = "hh-dashboard-band hh-band-note";

    /** The kind whose remembered names the names page lists, as its model id. */
    static final ParameterDefinition<String> KIND = ParameterDefinition.builder(String.class)
        .name("kind")
        .stringResolver(value -> value)
        .build();

    /** Where the names page starts: the last key of the page before it. */
    static final ParameterDefinition<String> AFTER = ParameterDefinition.builder(String.class)
        .name("after")
        .stringResolver(value -> value)
        .build();

    private LinkedServerPage() {
    }

    /** @return the server's page: its facts and, for a server this one manages, what this server remembers of it */
    static @NonNull RecordOverview<LinkedServerParts.ServerRow> overview() {
        return RecordOverview.<LinkedServerParts.ServerRow>fields(RecordOverview.SLUG,
                HohenheimMicrocopy.LINKED_SERVER.of("linked_server_overview"))
            .withoutFields()
            .widgets(LinkedServerPage::widgets);
    }

    private static @NonNull WidgetTree widgets(LinkedServerParts.@NonNull ServerRow server,
                                               @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        LocaleChain locales = conduit == null ? access.locales() : conduit.getLocales();
        MessageResolver resolver = conduit == null ? null : conduit.getMessageResolver();
        List<WidgetFact> facts = new ArrayList<>();
        facts.add(WidgetFact.badge(text(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_state"), locales,
            resolver), new WidgetBadge(server.state().label().resolve(locales, resolver),
            server.state().variant(), null, null, true)));
        Row linked = linkedOf(server);
        String address = linked == null ? ExternalUrls.configuredBase() : linked.get(LinkedServerModel.BASE_URL);
        facts.add(WidgetFact.of(text(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_address"), locales, resolver),
            address));
        facts.add(WidgetFact.of(text(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_version"), locales, resolver),
            server.version()));
        if (linked == null) {
            return new WidgetTree(List.of(AdminDashboard.section(factsCard(facts))));
        }
        Instant lastAnswer = server.lastAnswer();
        facts.add(lastAnswer == null
            ? WidgetFact.of(text(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_last_answer"), locales, resolver),
                text(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_last_answer_none"), locales, resolver))
            : WidgetFact.instant(text(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_last_answer"), locales,
                resolver), lastAnswer.toString()));
        boolean manages = LinkedServers.roleOf(linked) == LinkRole.MANAGES;
        Instant since = linked.get(LinkedServerModel.CREATED_AT);
        if (since != null) {
            String sinceKey = manages ? "linked_server_managed_here_since" : "linked_server_manages_here_since";
            facts.add(WidgetFact.of(text(HohenheimMicrocopy.LINKED_SERVER.of(sinceKey), locales, resolver),
                text(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_since_date").withArg("since", since), locales,
                    resolver)));
        }
        List<WidgetInstance> bands = new ArrayList<>();
        bands.add(AdminDashboard.section(factsCard(facts)));
        if (manages) {
            bands.addAll(remembered(linked, conduit, locales, resolver));
        }
        return new WidgetTree(bands);
    }

    /** The card of the server's facts, untitled: the page's heading names the server. */
    private static @NonNull WidgetInstance factsCard(@NonNull List<WidgetFact> facts) {
        return new WidgetInstance(CardWidget.ID, Map.of(), new WidgetTree(List.of(FactListWidget.of(facts))));
    }

    /**
     * "What Robbedoes remembers of Kuifje": since when it is in step (or that it was restored), the kinds this server
     * keeps a record of with their counts and the kinds the two versions do not share, as a card; then how changes
     * arrive, in muted words under it.
     */
    private static @NonNull List<WidgetInstance> remembered(@NonNull Row linked, @Nullable Conduit conduit,
                                                            @NonNull LocaleChain locales,
                                                            @Nullable MessageResolver resolver) {
        String name = String.valueOf((Object) linked.get(LinkedServerModel.NAME));
        String own = LinkedServers.ownName();
        String panelSlug = conduit == null ? HohenheimSlugs.ADMIN : CmsSupport.panelSlug(conduit);
        String key = String.valueOf((Object) linked.get(LinkedServerModel.ID));
        Map<Identifier, Long> counts = ClusterRecords.counts(linked);
        Panel panel = PanelRegistry.getBySlug(panelSlug);
        List<PanelEntry> walk = panel == null ? List.of() : sidebarWalk(panel);
        List<PlacedKind> placed = new ArrayList<>();
        for (Model model : SharedModels.served()) {
            Long count = counts.get(model.getModelId());
            if (count != null && count > 0) {
                placed.add(PlacedKind.of(panel, walk, model, new RememberedKind(kindWords(model, locales, resolver),
                    count, CmsRoutes.subpage(panelSlug, HohenheimSlugs.LINKED_SERVERS, key, Remembered.SLUG)
                        .with(KIND, model.getModelId().toString()))));
            }
        }
        placed.sort(Comparator.comparingInt(PlacedKind::walk).thenComparingInt(PlacedKind::declared)
            .thenComparing(kind -> kind.kind().kind(), String.CASE_INSENSITIVE_ORDER));
        List<RememberedKind> kinds = new ArrayList<>(placed.size());
        for (PlacedKind kind : placed) {
            kinds.add(kind.kind());
        }
        Instant inStep = linked.get(LinkedServerModel.IN_STEP_SINCE);
        Microcopy lead = LinkState.of(linked, LinkedServers.ownVersion()) == LinkState.RESTORED
            ? HohenheimMicrocopy.LINKED_SERVER.of("linked_server_remembers_restored").withArg("name", name)
            : inStep == null ? HohenheimMicrocopy.LINKED_SERVER.of("linked_server_remembers_not_yet")
            : HohenheimMicrocopy.LINKED_SERVER.of("linked_server_in_step_since").withArg("since", inStep);
        List<WidgetInstance> body = new ArrayList<>();
        body.add(new WidgetInstance(HohenheimWidgets.REMEMBERED.id(), Map.of()).withData(List.copyOf(kinds)));
        List<String> skipped = new ArrayList<>();
        for (Identifier kind : ClusterRecords.kinds(linked.get(LinkedServerModel.SKIPPED))) {
            Model model = SharedModels.model(kind);
            skipped.add(model == null ? kind.toString() : kindWords(model, locales, resolver));
        }
        if (!skipped.isEmpty()) {
            body.add(TextBlockWidget.of(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_remembers_skipped")
                .withArg("kinds", String.join(", ", skipped))));
        }
        WidgetInstance card = new WidgetInstance(CardWidget.ID, Map.of("title", HohenheimMicrocopy.LINKED_SERVER
            .of("linked_server_remembers_title").withArg("own", own).withArg("name", name), "lead", lead),
            new WidgetTree(body));
        WidgetInstance note = new WidgetInstance(SectionWidget.ID, Map.of("css_class", NOTE_BAND),
            new WidgetTree(List.of(TextBlockWidget.of(HohenheimMicrocopy.LINKED_SERVER
                .of("linked_server_remembers_foot").withArg("name", name).withArg("own", own)))));
        return List.of(AdminDashboard.section(card), note);
    }

    /** @return a kind's model noun in the plural, its first letter raised: the word a kind is listed by */
    private static @NonNull String kindWords(@NonNull Model model, @NonNull LocaleChain locales,
                                             @Nullable MessageResolver resolver) {
        return BlastString.upperFirst(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_kind_words")
            .withArg("kind", model.noun()).resolve(locales, resolver));
    }

    /**
     * The entries the panel's sidebar lists, in its declared order (by group, then nav order), a cluster's members at
     * the cluster's place in the cluster's own order.
     */
    private static @NonNull List<PanelEntry> sidebarWalk(@NonNull Panel panel) {
        List<PanelEntry> walk = new ArrayList<>();
        for (PanelNav.Section section : PanelNav.declaredSections(panel)) {
            for (PanelEntry entry : section.entries()) {
                if (entry instanceof PanelCluster cluster) {
                    for (String slug : cluster.members()) {
                        walk.add(Objects.requireNonNull(panel.entryBySlug(slug), "a cluster member is an entry"));
                    }
                } else {
                    walk.add(entry);
                }
            }
        }
        return walk;
    }

    /**
     * A remembered kind with its places, by which the page orders the kinds: its entry's place in the sidebar walk (or
     * that of the entry it stands under), then its entry's place among the panel's declared entries.
     */
    private record PlacedKind(int walk, int declared, @NonNull RememberedKind kind) {

        /** @return the kind placed past every place when the panel lists no entry of it in the sidebar walk */
        static @NonNull PlacedKind of(@Nullable Panel panel, @NonNull List<PanelEntry> walk, @NonNull Model model,
                                      @NonNull RememberedKind kind) {
            PanelResource<?> entry = panel == null ? null : panel.pickerResourceFor(model.getModelId());
            if (panel == null || entry == null) {
                return new PlacedKind(Integer.MAX_VALUE, Integer.MAX_VALUE, kind);
            }
            PanelEntry home = walk.contains(entry) ? entry : PanelNav.standsUnderEntryOf(panel, entry);
            int place = home == null ? -1 : walk.indexOf(home);
            return place < 0 ? new PlacedKind(Integer.MAX_VALUE, Integer.MAX_VALUE, kind)
                : new PlacedKind(place, panel.entries().indexOf(entry), kind);
        }
    }

    /** @return the linked server a row of the Servers list names, null for this server's own row or one gone */
    private static @Nullable Row linkedOf(LinkedServerParts.@NonNull ServerRow server) {
        return server.own() ? null : LinkedServers.byId(UUID.fromString(server.key()));
    }

    /** @return the linked server a row names when this server manages it, null otherwise */
    private static @Nullable Row managedOf(LinkedServerParts.@NonNull ServerRow server) {
        Row linked = linkedOf(server);
        return linked != null && LinkedServers.roleOf(linked) == LinkRole.MANAGES ? linked : null;
    }

    private static @NonNull String text(@NonNull Microcopy copy, @NonNull LocaleChain locales,
                                        @Nullable MessageResolver resolver) {
        return copy.resolve(locales, resolver);
    }

    /**
     * The names this server remembers of one kind of a server it manages, a page at a time: kept out of the tab strip,
     * opened from the kinds on the server's page, which stays the marked tab.
     */
    static final class Remembered implements RecordTab.Rendered<LinkedServerParts.ServerRow> {

        static final String SLUG = "remembered";

        @Override public @NonNull Identifier id() { return HohenheimIds.id("linked_server_remembered"); }
        @Override public @NonNull Microcopy label() {
            return HohenheimMicrocopy.LINKED_SERVER.of("linked_server_remembered");
        }
        @Override public @NonNull String slug() { return SLUG; }
        @Override public @NonNull Icon icon() { return Icon.of("database"); }
        @Override public boolean inTabs() { return false; }
        @Override public @Nullable String standsUnder() { return RecordOverview.SLUG; }

        @Override
        public @NonNull List<ParameterDefinition<?>> queryParameters() {
            return List.of(KIND, AFTER);
        }

        @Override
        public boolean visibleFor(LinkedServerParts.@NonNull ServerRow server, @NonNull AccessContext access) {
            return managedOf(server) != null;
        }

        @Override
        public @NonNull ActionResult<?> render(@NonNull PanelRequest request,
                                               LinkedServerParts.@NonNull ServerRow server) {
            Conduit conduit = request.conduit();
            Row linked = managedOf(server);
            Model model = SharedModels.model(Identifier.tryParse(conduit.getParameter(KIND)));
            if (linked == null || model == null) {
                throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "no remembered kind of that linked server");
            }
            LocaleChain locales = conduit.getLocales();
            MessageResolver resolver = conduit.getMessageResolver();
            String key = server.key();
            PeerRecords.KeptPage kept = PeerRecords.page(ClusterRecords.peerKey(linked), model.getModelId(),
                conduit.getParameter(AFTER), NAMES_PER_PAGE);
            List<RememberedName> names = new ArrayList<>(kept.entries().size());
            for (PeerRecords.KeptEntry entry : kept.entries()) {
                names.add(new RememberedName(entry.code() == null ? "" : entry.code(), entry.title()));
            }
            String panelSlug = CmsSupport.panelSlug(conduit);
            RouteTarget next = kept.next() == null ? null
                : CmsRoutes.subpage(panelSlug, HohenheimSlugs.LINKED_SERVERS, key, SLUG)
                    .with(KIND, model.getModelId().toString()).with(AFTER, kept.next());
            String heading = HohenheimMicrocopy.LINKED_SERVER.of("linked_server_remembered_heading")
                .withArg("kind", kindWords(model, locales, resolver)).withArg("own", LinkedServers.ownName())
                .withArg("name", server.name()).resolve(locales, resolver);
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", heading);
            vars.put("page", new RememberedNames(heading, names, next,
                CmsRoutes.subpage(panelSlug, HohenheimSlugs.LINKED_SERVERS, key, RecordOverview.SLUG)));
            vars.put("head", this.recordHead(conduit));
            return new RenderTemplateResult(HohenheimTemplateIds.LINKED_SERVER_REMEMBERED, vars);
        }
    }
}
