package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.server.proxy.SignedLink;
import be.elevenways.hohenheim.server.proxy.SignedLinkKeys;
import be.elevenways.hohenheim.site.SignedLinkCard;
import be.elevenways.hohenheim.site.SignedLinks;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.action.PageFormState;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.server.page.PageActions;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.People;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.security.ShownPerson;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Signed link tab of a path protected with a signed link: its secrets by their last characters with Replace the
 * secret and End now, how a link is made with an example signed with the current secret, and Make a link; a new
 * secret and a made link shown once to the viewer they were made for.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ProtectedPathSignedLinkPage implements RecordTab.Rendered<Row> {

    /** How far ahead the worked example's link expires; it is a real link, so it is kept short. */
    private static final Duration EXAMPLE_WORKS = Duration.ofHours(1);

    /** The file name the worked example opens under the protected path. */
    private static final String EXAMPLE_FILE = "example.zip";

    private static final PanelAction<Row> REPLACE = PanelAction.<Row, Microcopy>places(SignedLinks.REPLACE_SECRET,
            ActionPlacement.PAGE, (request, said) -> CmsActionResult.refreshWithToast(
                HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_replaced_toast")))
        .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_replace"),
            HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_replace_confirm")
                .withArg("days", SignedLinkSecrets.OLD_SECRET_WORKS.toDays()), ActionStyle.DEFAULT))
        .selectedByRoute(path -> String.valueOf((Object) path.get(ProtectedPathModel.ID)))
        .build();

    private static final PanelAction<Row> END_OLD = PanelAction.<Row, Microcopy>places(SignedLinks.END_OLD_SECRET,
            ActionPlacement.PAGE, (request, said) -> CmsActionResult.refreshWithToast(
                HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_ended_toast")))
        .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_end_old"),
            HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_end_old_confirm"), ActionStyle.DESTRUCTIVE))
        .selectedByRoute(path -> String.valueOf((Object) path.get(ProtectedPathModel.ID)))
        .build();

    /** The made link waits for the viewer, shown once on this tab as a new secret is. */
    private static final PanelAction<Row> MAKE = PanelAction.<Row, String>places(SignedLinks.MAKE_LINK,
            ActionPlacement.PAGE, (request, link) -> {
                Integer listId = request.subject().get(ProtectedPathModel.ACCESS_LIST_ID);
                if (link != null && listId != null) {
                    TokenDisclosures.SIGNED_LINKS.replace(request.request().access(), listId, link);
                }
                return CmsActionResult.refreshWithToast(HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_made_toast"));
            })
        .confirmation(ConfirmationSpec.of(HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_make"),
            HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_make"),
            HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_make_lead"), ActionStyle.DEFAULT))
        .selectedByRoute(path -> String.valueOf((Object) path.get(ProtectedPathModel.ID)))
        .build();

    @Override public @NonNull Identifier id() { return HohenheimIds.id("protected_path_signed_link"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_tab"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.SIGNED_LINK; }
    @Override public @NonNull Icon icon() { return Icon.of("link"); }
    @Override public @NonNull Microcopy lead() { return HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_lead"); }

    /** Only a path protected with a signed link has the tab; any other path hides it and its route answers 404. */
    @Override
    public boolean visibleFor(@NonNull Row path, @NonNull AccessContext access) {
        return SignedLinkSecrets.ruleOf(path) != null;
    }

    @Override
    public @NonNull List<PanelAction<Row>> actions() {
        return List.of(REPLACE, END_OLD, MAKE);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row path) {
        Conduit conduit = request.conduit();
        AccessContext viewer = request.access();
        Row rule = Objects.requireNonNull(SignedLinkSecrets.ruleOf(path), "the tab shows only on a signed path");
        int listId = Objects.requireNonNull(path.get(ProtectedPathModel.ACCESS_LIST_ID), "a guarded path's list");
        SignedLinkKeys held = SignedLinkSecrets.keysOf(rule);
        Instant now = Now.instant();
        SignedLinkKeys.Made current = held.current();
        SignedLinkKeys.Made old = held.oldInForce(now);

        People.Page people = People.page(viewer);
        for (SignedLinkKeys.Made made : new SignedLinkKeys.Made[]{current, old}) {
            if (made != null && made.madeBy() != null) people.ask(PrincipalRef.account(made.madeBy()));
        }
        List<SignedLinkCard.Key> keys = new ArrayList<>();
        if (current != null) keys.add(key(current, people, null));
        if (old != null) keys.add(key(old, people, form(request, path, END_OLD)));

        String prefix = String.valueOf((Object) path.get(ProtectedPathModel.PATH));
        String howTo = current == null ? "" : howTo(conduit, SignedLinkSecrets.addressOf(path), prefix,
            current.secret(), now);
        String newSecret = Objects.toString(TokenDisclosures.SIGNED_LINK_SECRETS.claim(viewer, listId), "");
        String madeLink = Objects.toString(TokenDisclosures.SIGNED_LINKS.claim(viewer, listId), "");
        SignedLinkCard card = new SignedLinkCard(newSecret, keys, form(request, path, REPLACE), howTo,
            form(request, path, MAKE), madeLink, madeLink.isEmpty() ? null : SignedLinkSecrets.expiresOf(madeLink));

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_page_title").withArg("name", prefix)
            .asString(conduit));
        vars.put("head", recordHead(conduit));
        vars.put("card", card);
        vars.put("oldDays", SignedLinkSecrets.OLD_SECRET_WORKS.toDays());
        return new RenderTemplateResult(HohenheimTemplateIds.PROTECTED_PATH_SIGNED_LINK, vars);
    }

    /** @return one secret as the table lists it */
    private static SignedLinkCard.@NonNull Key key(SignedLinkKeys.@NonNull Made made, People.@NonNull Page people,
                                                   @Nullable PageFormState end) {
        ShownPerson by = made.madeBy() == null ? ShownPerson.withheld() : people.shown(PrincipalRef.account(
            made.madeBy()));
        return new SignedLinkCard.Key(made.endsIn(), made.madeAt(), by, made.until(), end);
    }

    /**
     * @return the exact explanation of a link, its example a real link to a file under the path, signed with the
     *         current secret and working for {@link #EXAMPLE_WORKS}
     */
    private static @NonNull String howTo(@NonNull Conduit conduit, @Nullable String address, @NonNull String prefix,
                                         @NonNull String secret, @NonNull Instant now) {
        String examplePath = Objects.requireNonNull(SignedLink.rawPathOf(prefix + "/" + EXAMPLE_FILE),
            "a protected prefix and a plain file name make a path");
        long expires = now.plus(EXAMPLE_WORKS).getEpochSecond();
        return HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_how")
            .withArg("address", address == null ? "" : address)
            .withArg("example_path", examplePath)
            .withArg("example_expires", String.valueOf(expires))
            .withArg("example_link", SignedLink.link(address, examplePath, expires, secret))
            .asString(conduit);
    }

    /** @return the tab action's form over the path, null when the operation withholds it from the viewer */
    private @Nullable PageFormState form(@NonNull PanelRequest request, @NonNull Row path,
                                         @NonNull PanelAction<Row> action) {
        try {
            return PageActions.open(request, this, path, action.id()) instanceof PageActions.Form form
                ? form.state() : null;
        } catch (DomainRefusal withheld) {
            return null;
        }
    }
}
