package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.traffic.ActivityAsk;
import be.elevenways.hohenheim.traffic.TvLinksCard;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.TextOutput;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.ShareLinkModel;
import be.elevenways.zenit.auth.server.ShareLink;
import be.elevenways.zenit.auth.server.ShareLinks;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.action.PageFormState;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.cms.server.page.PageActions;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.People;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.security.ShownPerson;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Making and ending TV links (administrators), their card on the Traffic page's Activity tab, and the TV page a link
 * opens: the one pair of operations the panel and the assistant tools both run.
 *
 * AIDEV-NOTE: a link's address is a credential, made once: the make parks it for the account behind the call
 * ({@link TokenDisclosures#TV_LINKS}), and the Activity tab shows it to that account once, so neither a tool's answer
 * nor a read ever carries it. A call no account stands behind is refused before anything is made. The TV page answers
 * the framework's ordinary not-found for a wrong, ended or expired token alike, never a hint that a link existed.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class TrafficTvLinks {

    /** The make's argument naming the one site a link shows; left out, every site. */
    public static final String SITE = "site";

    /** The end's argument naming the link, by its code. */
    public static final String CODE = "code";

    private static final FormEntry SITE_ENTRY = OperationForms.recordChoice(SITE,
        HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_site"), HohenheimMicrocopy.HELP.of("tv_link_site"),
        SiteModel.MODEL_ID, OptionSource.dynamic(context -> sites()), false);

    private static final FormEntry CODE_ENTRY = OperationForms.recordId(CODE,
        HohenheimMicrocopy.HELP.of("tv_link_code"), ShareLinkModel.MODEL_ID, true);

    /** The make's input: the site a link shows, null for every site. */
    public record MakeInput(@Nullable Integer site) {
    }

    /** Makes a TV link of every site or one; its address waits, shown once, for the account that made it. */
    public static final Operation<Void, MakeInput, UUID> MAKE = Operation.declare(HohenheimIds.id("make_tv_link"))
        .happened(OperationSentences.of("make_tv_link"))
        .label(HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_make"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("make_tv_link_description"))
        .icon(Icon.of("tv"))
        .noSubject()
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.of(FormSpec.builder().add(SITE_ENTRY).build(), MakeInput.class,
            values -> new MakeInput(RawValues.positiveInt(values.get(SITE)))))
        .result(UUID.class)
        .command(OperationCommand.perVerb().onDatasource("default"))
        .register();

    /** Ends a TV link: its address answers like any unknown one from then on, and an open TV page stops at once. */
    public static final Operation<Void, OperationInput.Values, Void> END =
            Operation.declare(HohenheimIds.id("end_tv_link"))
        .happened(OperationSentences.of("end_tv_link"))
        .label(HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_end"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("end_tv_link_description"))
        .icon(Icon.of("xmark"))
        .noSubject()
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.derived(FormSpec.builder().add(CODE_ENTRY).build()))
        .command(OperationCommand.perVerb().onDatasource("default"))
        .register();

    /** The make, on the Activity tab's TV page card. */
    static final PanelAction<Object> MAKE_ACTION = PanelAction.<Object, UUID>places(MAKE, ActionPlacement.PAGE,
            (request, link) -> CmsActionResult.refreshWithToast(
                HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_made")))
        .confirmation(ConfirmationSpec.builder().title(HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_make"))
            .confirmLabel(HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_make")).build())
        .build();

    /** The end, once per listed link, the link carried hidden. */
    static final PanelAction<Object> END_ACTION = PanelAction.<Object, Void>places(END, ActionPlacement.PAGE,
            (request, ended) -> CmsActionResult.refreshWithToast(
                HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_ended")))
        .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_end"),
            HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_end_confirm"), ActionStyle.DESTRUCTIVE))
        .transport(CODE)
        .build();

    static {
        OperationHandlers.attach(MAKE).handle(call -> {
            AccessContext caller = Objects.requireNonNull(call.access(), "a TV link is made by its caller");
            if (!TokenDisclosures.accountOf(caller)) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("tv_link_no_account"));
            }
            Integer site = call.input() == null ? null : call.input().site();
            String scope = site == null ? ActivityAsk.every().spelled()
                : ActivityAsk.site(HohenheimTraffic.sourceOf(site)).spelled();
            int server = ControllerIdentity.recordId();
            ShareLinks.Minted minted;
            try {
                minted = ShareLinks.create(caller, TrafficTvLinkLevel.LEVEL, server, null, scope);
            } catch (Violations refused) {
                throw refused.renamed(Map.of(ShareLinks.ENTRY_NAME, SITE));
            }
            Objects.requireNonNull(TokenDisclosures.TV_LINKS.park(caller, server, minted.token()),
                "an account stands behind the caller");
            return minted.link().id();
        });
        OperationHandlers.attach(END).handle(call -> {
            AccessContext caller = Objects.requireNonNull(call.access(), "a TV link is ended by its caller");
            Object code = call.input() == null ? null : call.input().get(CODE);
            UUID linkId = code == null ? null : Uuids.canonical(String.valueOf(code));
            int ended = linkId == null ? 0 : ShareLinks.revokeAt(caller, TrafficTvLinkLevel.LEVEL,
                ControllerIdentity.recordId(), link -> link.id().equals(linkId));
            if (ended == 0) {
                throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "no TV link " + code + " to end");
            }
            return null;
        });
    }

    private TrafficTvLinks() {
    }

    /** Answers the TV page; called at the MODULES boot stage. */
    public static void init() {
        HohenheimEndpoints.TV_PAGE.setHandler(conduit -> {
            String token = conduit.getParameter(HohenheimEndpoints.TV_TOKEN);
            AccessContext holder = TrafficTvLinkLevel.holderOf(token);
            ActivityAsk shows = holder == null ? null : TrafficTvLinkLevel.showsTo(holder);
            if (shows == null) {
                conduit.notFound();
                return null;
            }
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_title").asString(conduit));
            vars.put("view", TrafficActivityPage.view(shows, ActivityAsk.tv(Objects.requireNonNull(token)).spelled(),
                false, true));
            vars.put("apps", AppsSendingReads.onTv(AccessContext.of(conduit), holder, shows));
            // The link's token is the page's address already; the apps picture's re-reads present it.
            vars.put("credential", token);
            return new RenderTemplateResult(HohenheimTemplateIds.TRAFFIC_TV, vars);
        });
    }

    /**
     * @param names every site's name by its source, as the tab's picture read them
     * @return the card for the viewer: the make form, every live link with its end, and addresses made for them
     */
    static @NonNull TvLinksCard card(@NonNull PanelRequest request, @NonNull PanelPage page,
                                     @NonNull Map<String, String> names) {
        AccessContext viewer = request.access();
        int server = ControllerIdentity.recordId();
        List<ShareLink> links = live();
        // One read of every maker's name and one of every code, whatever the number of links.
        People.Page people = People.page(viewer);
        List<String> keys = new ArrayList<>();
        for (ShareLink link : links) {
            if (link.createdBy() != null) people.ask(PrincipalRef.account(link.createdBy()));
            keys.add(link.id().toString());
        }
        Map<String, String> codes = RecordCodes.codes(ShareLinkModel.MODEL_ID, keys, viewer);
        List<TvLinksCard.Link> listed = new ArrayList<>();
        for (ShareLink link : links) {
            String key = link.id().toString();
            listed.add(new TvLinksCard.Link(codes.getOrDefault(key, key), shows(link, names, request.conduit()),
                link.createdBy() == null ? ShownPerson.withheld() : people.shown(PrincipalRef.account(
                    link.createdBy())), link.createdAt(), form(request, page, END_ACTION,
                    Map.<String, Object>of(CODE, key))));
        }
        String parked = TokenDisclosures.TV_LINKS.claim(viewer, server);
        List<String> addresses = new ArrayList<>();
        if (parked != null) {
            for (String token : parked.split("\n")) {
                if (!token.isBlank()) addresses.add(TrafficTvLinkLevel.urlFor(request.conduit(), token.strip()));
            }
        }
        return new TvLinksCard(form(request, page, MAKE_ACTION, Map.of()), listed, String.join("\n", addresses));
    }

    /**
     * The TV links of this server in force as the list tool lists them (list kind tv_link, their tools' word, never
     * zenit-auth's share_link): by code, titled by what each shows and who made it, with when it was made; never an
     * address.
     */
    public static RecordSource.@NonNull Builder<ShareLinkModel> listed() {
        return RecordSource.of(ShareLinkModel.REF).assistantNoun("tv_link")
            .project(ShareLinkModel.CREATED_AT)
            .scopedBy(RowScope.within(() -> ShareLinkModel.ID.in(live().stream().map(ShareLink::id).toList())))
            .titles(TrafficTvLinks::titles);
    }

    /** @return this server's TV links in force, newest first */
    private static @NonNull List<ShareLink> live() {
        List<ShareLink> links = new ArrayList<>();
        for (ShareLink link : ShareLinks.listAt(TrafficTvLinkLevel.LEVEL, ControllerIdentity.recordId())) {
            if (link.isLive(Now.instant())) links.add(link);
        }
        return links;
    }

    /** @return each listed link's title, what it shows and who made it, its sites and makers read once a page */
    private static @NonNull List<@Nullable String> titles(@NonNull List<Row> rows, @Nullable LocaleChain chain,
                                                          @Nullable AccessContext reader) {
        LocaleChain locales = chain != null ? chain : reader != null ? reader.locales() : null;
        List<String> titles = new ArrayList<>();
        if (locales == null || reader == null) {
            for (Row row : rows) titles.add(null);
            return titles;
        }
        Map<String, String> names = new HashMap<>();
        for (Row site : Models.get(SiteModel.class).find().all()) {
            names.put(HohenheimTraffic.sourceOf(site.get(SiteModel.ID)), site.get(SiteModel.NAME));
        }
        People.Page people = People.page(reader);
        for (Row row : rows) {
            UUID by = row.get(ShareLinkModel.CREATED_BY);
            if (by != null) people.ask(PrincipalRef.account(by));
        }
        for (Row row : rows) {
            UUID by = row.get(ShareLinkModel.CREATED_BY);
            ShownPerson maker = by == null ? ShownPerson.withheld() : people.shown(PrincipalRef.account(by));
            Microcopy title = HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_listed")
                .withArg("shows", shows(TrafficTvLinkLevel.shows(row.get(ShareLinkModel.SCOPE)), names)
                    .resolve(locales, Zenit.getMessageResolver()))
                .withArg("person", maker.words(ListedText.TITLE).resolve(locales, Zenit.getMessageResolver()));
            titles.add(title.resolve(locales, Zenit.getMessageResolver()));
        }
        return titles;
    }

    /**
     * The one place a TV link's maker leaves as plain text: the list tool's title of the link.
     *
     * AIDEV-NOTE: a TextOutput is the inventory of places a drawn person is flattened (protoblast TextOutput); an
     * assistant's list has no chip to draw.
     */
    private enum ListedText implements TextOutput {
        TITLE
    }

    /** @return what a listed link shows, in the viewer's words: every site, a site's name, or a site gone */
    private static @NonNull String shows(@NonNull ShareLink link, @NonNull Map<String, String> names,
                                         @NonNull Conduit conduit) {
        return shows(TrafficTvLinkLevel.shows(link), names).asString(conduit);
    }

    /** @return what a link showing this ask shows: every site, a site's name, or a site gone */
    private static @NonNull Microcopy shows(@Nullable ActivityAsk shows, @NonNull Map<String, String> names) {
        return shows == null || shows.kind() != ActivityAsk.Kind.SITE
            ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("tv_link_every_site")
            : names.containsKey(shows.value()) ? Microcopy.literal(names.get(shows.value()))
            : HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_removed_site");
    }

    /** @return the page action's form, null when the operation withholds it from the viewer */
    private static @Nullable PageFormState form(@NonNull PanelRequest request, @NonNull PanelPage page,
                                                @NonNull PanelAction<Object> action,
                                                @NonNull Map<String, Object> presets) {
        try {
            return PageActions.open(request, page, action.id(), presets) instanceof PageActions.Form form
                ? form.state() : null;
        } catch (DomainRefusal withheld) {
            return null;
        }
    }

    /** @return every site a link may show, by name */
    private static @NonNull List<FieldOption<String>> sites() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (Row site : Models.get(SiteModel.class).find().orderBy(SiteModel.NAME, SortOrder.ASC).all()) {
            options.add(FieldOption.of(String.valueOf((Object) site.get(SiteModel.ID)),
                Microcopy.literal(site.get(SiteModel.NAME))));
        }
        return options;
    }
}
