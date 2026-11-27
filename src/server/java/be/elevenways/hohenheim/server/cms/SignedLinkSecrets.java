package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.SignedLink;
import be.elevenways.hohenheim.server.proxy.SignedLinkKeys;
import be.elevenways.hohenheim.site.SignedLinks;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.Authorizer;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.RecordReadLines;
import be.elevenways.zenit.server.security.AccountDisclosures;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A signed link's secrets over their life: made by Hohenheim, parked once for the account that caused it, replaced
 * with the old one kept for {@link #OLD_SECRET_WORKS}, ended early on request; and the links made with them.
 *
 * AIDEV-NOTE: at most two secrets are in force. A replacement while an old one still works ends that old one at once:
 * the one just replaced is the secret links in flight were signed with. A new secret waits for its maker on the path's
 * Signed link tab ({@link TokenDisclosures#SIGNED_LINK_SECRETS}, keyed by the path's list, which exists before the
 * path row does), never in an answer, a read or history; a call no account stands behind is refused before anything
 * is written.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SignedLinkSecrets {

    /** How long the old secret keeps signing after a replacement. */
    public static final Duration OLD_SECRET_WORKS = Duration.ofDays(7);

    /** The entropy of a made secret; its text is the URL-safe base64 of these bytes. */
    private static final int SECRET_BYTES = 32;

    /** The read line naming how one gets in at a protected path ({@link AccessRuleSummaries#wayInOf}). */
    static final String WAY_LINE = "way_in";

    /** The read line naming a signed path's secrets by their last characters. */
    static final String READ_LINE = "signed_link";

    /** The read line saying a new secret waits for the reader, where and until when. */
    static final String WAITING_LINE = "signed_link_secret";

    static {
        OperationHandlers.attach(SignedLinks.REPLACE_SECRET)
            .applies(path -> ruleOf(path) != null)
            .authorize(managesItsSite())
            .handle(call -> replace(call.subject(), Objects.requireNonNull(call.access(), "a replace has a caller")));
        OperationHandlers.attach(SignedLinks.END_OLD_SECRET)
            .applies(path -> {
                Row rule = ruleOf(path);
                return rule != null && keysOf(rule).oldInForce(Now.instant()) != null;
            })
            .authorize(managesItsSite())
            .handle(call -> endOld(call.subject()));
        OperationHandlers.attach(SignedLinks.MAKE_LINK)
            .applies(path -> ruleOf(path) != null)
            .authorize(managesItsSite())
            .handle(call -> make(call.subject(), Objects.requireNonNull(call.input(), "a link has its input")));
        RecordReadLines.register(ProtectedPathModel.MODEL_ID, SignedLinkSecrets.class, SignedLinkSecrets::readLines);
    }

    private SignedLinkSecrets() {
    }

    /** Loads the class and the operations it handles; idempotent. */
    public static void init() {
        SignedLinks.init();
    }

    /**
     * A new signed-link rule's data and the secret in it.
     *
     * @param data   the rule's data, the secret in it
     * @param secret the secret, to park once the rule is stored
     */
    record NewSecret(@NonNull Map<String, Object> data, @NonNull String secret) {

        @Override
        public @NonNull String toString() {
            return "NewSecret[secret withheld]";
        }
    }

    /**
     * @return the data of a new signed-link rule holding a fresh secret made by the caller
     * @throws Violations when no account stands behind the caller to be shown the secret
     */
    static @NonNull NewSecret first(@Nullable AccessContext caller) {
        AccessContext maker = requireShownTo(caller);
        SignedLinkKeys.Made made = fresh(maker);
        return new NewSecret(new SignedLinkKeys(made, null).toData(), made.secret());
    }

    /** Parks a rule's new secret for its maker on the path's Signed link tab, replacing any unread one. */
    static void parkNew(@NonNull AccessContext caller, int listId, @NonNull String secret) {
        Objects.requireNonNull(TokenDisclosures.SIGNED_LINK_SECRETS.replace(caller, listId, secret),
            "an account stands behind the caller");
    }

    /** @return the enabled signed-link rule of the path's list, null when the path is not protected that way */
    static @Nullable Row ruleOf(@NonNull Row path) {
        Integer listId = path.get(ProtectedPathModel.ACCESS_LIST_ID);
        if (listId == null) return null;
        return Models.get(AccessRuleModel.class).find()
            .where(AccessRuleModel.ACCESS_LIST_ID.eq(listId))
            .and(AccessRuleModel.TYPE.eq(AccessRuleModel.TYPE_SIGNED_LINK))
            .and(AccessRuleModel.ENABLED.eq(true))
            .orderBy(AccessRuleModel.ID, SortOrder.ASC)
            .first();
    }

    /** @return the secrets a signed-link rule holds */
    static @NonNull SignedLinkKeys keysOf(@NonNull Row rule) {
        return SignedLinkKeys.of(AccessRuleModel.dataOf(rule));
    }

    /** @return the address of the path's site with its scheme, null while visitors reach none of it */
    static @Nullable String addressOf(@NonNull Row path) {
        Integer siteId = path.get(ProtectedPathModel.SITE_ID);
        Row site = siteId == null ? null : Models.get(SiteModel.class).findById(siteId);
        return site == null ? null : AppHealth.addressOf(site);
    }

    /** @return when a link this class made stops working, null for a text that is no such link */
    public static @Nullable Instant expiresOf(@NonNull String link) {
        String expires = new Uri(link).getQueryParam(SignedLink.EXPIRES);
        try {
            return expires == null ? null : Instant.ofEpochSecond(Long.parseLong(expires));
        } catch (NumberFormatException | DateTimeException notALink) {
            return null;
        }
    }

    /** The new secret replaces the current one, which keeps working for {@link #OLD_SECRET_WORKS}. */
    private static @NonNull Microcopy replace(@NonNull Row path, @NonNull AccessContext caller) {
        requireShownTo(caller);
        Row rule = Objects.requireNonNull(ruleOf(path), "applies only to a path with a signed-link rule");
        SignedLinkKeys keys = keysOf(rule);
        Instant now = Now.instant();
        SignedLinkKeys.Made current = keys.current();
        SignedLinkKeys.Made old = current == null ? null : new SignedLinkKeys.Made(current.secret(), current.madeAt(),
            current.madeBy(), now.plus(OLD_SECRET_WORKS));
        SignedLinkKeys.Made made = fresh(caller);
        rule.set(AccessRuleModel.DATA, new SignedLinkKeys(made, old).toData());
        Models.get(AccessRuleModel.class).save(rule);
        int listId = Objects.requireNonNull(path.get(ProtectedPathModel.ACCESS_LIST_ID), "a guarded path's list");
        Instant shownUntil = Objects.requireNonNull(TokenDisclosures.SIGNED_LINK_SECRETS.replace(caller, listId,
            made.secret()), "an account stands behind the caller");
        Microcopy words = HohenheimMicrocopy.PROTECTED_PATH.of(old == null ? "signed_link_replaced_alone"
                : "signed_link_replaced")
            .withArg("path", String.valueOf((Object) path.get(ProtectedPathModel.PATH)))
            .withArg("account", TokenDisclosures.shownTo(caller)).withArg("until", shownUntil);
        return old == null ? words : words.withArg("old_until", Objects.requireNonNull(old.until()));
    }

    /** The old secret stops signing now. */
    private static @NonNull Microcopy endOld(@NonNull Row path) {
        Row rule = Objects.requireNonNull(ruleOf(path), "applies only to a path with a signed-link rule");
        rule.set(AccessRuleModel.DATA, new SignedLinkKeys(keysOf(rule).current(), null).toData());
        Models.get(AccessRuleModel.class).save(rule);
        return HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_old_ended")
            .withArg("path", String.valueOf((Object) path.get(ProtectedPathModel.PATH)));
    }

    /**
     * @return one link to the path, signed with the current secret
     * @throws Violations naming {@code path} for one outside the protected path (the file's full path in the site is
     *                    asked, as the file tools take it), {@code minutes} for a length out of range
     */
    private static @NonNull String make(@NonNull Row path, SignedLinks.@NonNull MakeInput input) {
        Row rule = Objects.requireNonNull(ruleOf(path), "applies only to a path with a signed-link rule");
        SignedLinkKeys.Made current = Objects.requireNonNull(keysOf(rule).current(),
            "an enabled signed-link rule holds its secret");
        String prefix = String.valueOf((Object) path.get(ProtectedPathModel.PATH));
        String raw = SignedLink.rawPathOf(input.path());
        if (raw == null || !SignedLink.under(prefix, raw)) {
            throw Violations.ofField(SignedLinks.PATH.getName(), input.path(),
                HohenheimMicrocopy.VIOLATIONS.of("signed_link_path_outside").withArg("prefix", prefix)
                    .withArg("example", exampleUnder(prefix, input.path())));
        }
        int minutes = input.minutes() == null ? SignedLinks.DEFAULT_MINUTES : input.minutes();
        if (minutes < 1 || minutes > SignedLinks.LONGEST_MINUTES) {
            throw Violations.ofField(SignedLinks.MINUTES.getName(), minutes,
                HohenheimMicrocopy.VIOLATIONS.of("signed_link_minutes_range")
                    .withArg("longest", SignedLinks.LONGEST_MINUTES));
        }
        long expires = Now.instant().getEpochSecond() + Duration.ofMinutes(minutes).toSeconds();
        return SignedLink.link(addressOf(path), raw, expires, current.secret());
    }

    /** @return the given path's last part under {@code prefix}: the full path a refusal shows as its example */
    private static @NonNull String exampleUnder(@NonNull String prefix, @NonNull String given) {
        String name = given.strip();
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isEmpty()) {
            name = "report.pdf";
        }
        return prefix.endsWith("/") ? prefix + name : prefix + "/" + name;
    }

    /** @return a new secret made now by the caller's account */
    private static SignedLinkKeys.@NonNull Made fresh(@NonNull AccessContext caller) {
        return new SignedLinkKeys.Made(SecureTokens.randomToken(SECRET_BYTES), Now.instant(),
            AccountDisclosures.accountOf(caller), null);
    }

    /** @throws Violations when no account stands behind the caller to be shown a secret */
    private static @NonNull AccessContext requireShownTo(@Nullable AccessContext caller) {
        if (caller == null || !TokenDisclosures.accountOf(caller)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("signed_link_no_account"));
        }
        return caller;
    }

    /** Only who manages the path's site acts on it; anyone else is told it does not exist. */
    private static <I> @NonNull Authorizer<Row, I> managesItsSite() {
        return (path, input, access) -> {
            Integer siteId = path.get(ProtectedPathModel.SITE_ID);
            Row site = siteId == null ? null : Models.get(SiteModel.class).findById(siteId);
            if (site != null && SiteOperationHandlers.reaches(site, access)) return null;
            return site != null ? SiteOperationHandlers.concealed(site)
                : new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "protected path " + path.get(ProtectedPathModel.ID)
                    + " has no site");
        };
    }

    /**
     * A read's lines for each protected path: how one gets in, then, for a path guarded by a signed link, its secrets
     * by their last characters.
     */
    private static @NonNull Map<String, List<RecordReadLines.Line>> readLines(@NonNull Collection<String> keys,
                                                                              @NonNull AccessContext reader) {
        List<Integer> ids = new ArrayList<>();
        for (String key : keys) ids.add(Integer.valueOf(key));
        Map<Integer, String> pathByList = new HashMap<>();
        Map<String, List<RecordReadLines.Line>> lines = new HashMap<>();
        for (Row path : Models.get(ProtectedPathModel.class).find().where(ProtectedPathModel.ID.in(ids)).all()) {
            String pathKey = String.valueOf((Object) path.get(ProtectedPathModel.ID));
            lines.computeIfAbsent(pathKey, ignored -> new ArrayList<>()).add(new RecordReadLines.Line(WAY_LINE,
                reader.text(AccessRuleSummaries.wayInOf(path))));
            Integer listId = path.get(ProtectedPathModel.ACCESS_LIST_ID);
            if (listId != null) pathByList.put(listId, pathKey);
        }
        if (pathByList.isEmpty()) return lines;
        Set<String> told = new HashSet<>();
        Instant now = Now.instant();
        Map<Integer, Instant> waiting = TokenDisclosures.SIGNED_LINK_SECRETS.waiting(reader, pathByList.keySet());
        for (Row rule : Models.get(AccessRuleModel.class).find()
                .where(AccessRuleModel.ACCESS_LIST_ID.in(new ArrayList<>(pathByList.keySet())))
                .and(AccessRuleModel.TYPE.eq(AccessRuleModel.TYPE_SIGNED_LINK))
                .and(AccessRuleModel.ENABLED.eq(true)).orderBy(AccessRuleModel.ID, SortOrder.ASC).all()) {
            String pathKey = pathByList.get(rule.get(AccessRuleModel.ACCESS_LIST_ID));
            if (pathKey == null || !told.add(pathKey)) continue;
            SignedLinkKeys held = keysOf(rule);
            SignedLinkKeys.Made old = held.oldInForce(now);
            Microcopy words = held.current() == null ? HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_read_none")
                : old == null ? HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_read")
                    .withArg("end", held.current().endsIn())
                : HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_read_old")
                    .withArg("end", held.current().endsIn()).withArg("old", old.endsIn())
                    .withArg("until", Objects.requireNonNull(old.until()));
            List<RecordReadLines.Line> said = lines.computeIfAbsent(pathKey, ignored -> new ArrayList<>());
            said.add(new RecordReadLines.Line(READ_LINE, reader.text(words)));
            Instant shownUntil = waiting.get(rule.get(AccessRuleModel.ACCESS_LIST_ID));
            if (shownUntil != null) {
                said.add(new RecordReadLines.Line(WAITING_LINE, reader.text(HohenheimMicrocopy.PROTECTED_PATH
                    .of("signed_link_read_waiting").withArg("account", TokenDisclosures.shownTo(reader))
                    .withArg("until", shownUntil))));
            }
        }
        return lines;
    }
}
