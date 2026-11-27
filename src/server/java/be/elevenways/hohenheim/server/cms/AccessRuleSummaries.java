package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * THE localized rendering of one access rule: what the node decides, in one line, plus
 * the type's own declared label.
 *
 * AIDEV-NOTE: this lives beside the surfaces rather than inside {@link AccessRuleModel}
 * because it is COPY, not data -- the model's {@code search_text} column is the data half
 * and is deliberately never a label. It is shared because the rules tab, the rule list
 * and the record heading must not each invent their own sentence for the same row: three
 * spellings of "allows 10.0.0.0/8" is exactly the drift the finding reported.
 */
final class AccessRuleSummaries {

    private AccessRuleSummaries() {}

    /**
     * What the node decides, in one localized line.
     *
     * @param type the rule's stored type token, read off the row by the caller
     */
    static @NonNull Microcopy summaryOf(@NonNull Row rule, @Nullable String type) {
        Map<String, Object> data = AccessRuleModel.dataOf(rule);
        return switch (type == null ? "" : type) {
            case AccessRuleModel.TYPE_GROUP -> HohenheimMicrocopy.ACCESS_RULE.of(
                AccessListModel.SATISFY_ALL.equals(
                    Texts.trimmedOrNull(data.get(AccessRuleModel.GROUP_SATISFY.getName())))
                    ? "summary_group_all" : "summary_group_any");
            // A value the rule does not carry yet says so, never a label followed by nothing ("Password: Username").
            case AccessRuleModel.TYPE_IP_ALLOW, AccessRuleModel.TYPE_IP_DENY -> valued("summary_network",
                "network", data.get(AccessRuleModel.NETWORK.getName()), "summary_network_missing");
            case AccessRuleModel.TYPE_BASIC_AUTH -> valued("summary_basic_auth",
                "username", data.get(AccessRuleModel.BASIC_AUTH_USERNAME.getName()), "summary_basic_auth_missing");
            case AccessRuleModel.TYPE_AUTH_PROVIDER -> {
                String permission = Texts.trimmedOrNull(
                    data.get(AccessRuleModel.PROVIDER_REQUIRED_PERMISSION.getName()));
                Microcopy summary = HohenheimMicrocopy.ACCESS_RULE.of(permission == null
                    ? "summary_auth_provider" : "summary_auth_provider_permission")
                    .withArg("provider", providerName(data.get(AccessRuleModel.PROVIDER_ID.getName())));
                yield permission == null ? summary : summary.withArg("permission", permission);
            }
            case AccessRuleModel.TYPE_SIGNED_LINK -> HohenheimMicrocopy.ACCESS_RULE.of("summary_signed_link");
            default -> HohenheimMicrocopy.ACCESS_RULE.of("summary_unknown");
        };
    }

    /**
     * The rule's type label and its summary as one line, for the surfaces that have room
     * for neither a badge nor a second row (the record heading, the list's name cell).
     *
     * @return the line in the reader's language, null for a rule without a type
     */
    static @Nullable Microcopy titleOf(@NonNull Row rule) {
        String type = rule.get(AccessRuleModel.TYPE);
        if (type == null) {
            return null;
        }
        return HohenheimMicrocopy.ACCESS_RULE.of("summary_line")
            .withArg("kind", CmsSupport.enumValueLabel(AccessRuleModel.TYPE, type))
            .withArg("summary", summaryOf(rule, type));
    }

    /**
     * THE on/off cue: the same two words, icons and pill in the rules tab and in the rule
     * list, so a rule that is switched on says so instead of being told apart by the
     * ABSENCE of an "Off" chip.
     *
     * AIDEV-NOTE: rendered through zenit-cms' enum-badge cell partial on both surfaces --
     * the state is a two-member vocabulary, and a hand-written pill per surface is how
     * "on" ended up invisible in the first place. The colour is a second cue, never the
     * only one: both members carry their own word and their own icon.
     */
    static @NonNull EnumBadgeState enabledBadge(boolean enabled) {
        return enabled
            ? new EnumBadgeState("on", HohenheimMicrocopy.ACCESS_RULE.of("state_on"), null, "check",
                BadgeVariant.SUCCESS, null, true)
            : new EnumBadgeState("off", HohenheimMicrocopy.ACCESS_RULE.of("state_off"), null, "xmark",
                BadgeVariant.SECONDARY, null, true);
    }

    /** The on/off cue of one stored rule row. */
    static @NonNull EnumBadgeState enabledBadge(@NonNull Row rule) {
        return enabledBadge(Boolean.TRUE.equals(rule.get(AccessRuleModel.ENABLED)));
    }

    private static @NonNull String providerName(@Nullable Object providerId) {
        if (!(providerId instanceof Number number)) {
            return "";
        }
        Row provider = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.ID.eq(number.intValue())).first();
        return provider != null ? Objects.toString(provider.get(SiteAuthProviderModel.NAME), "") : "";
    }

    /** @return the summary carrying the value, or the missing-value sentence when the rule has none yet */
    private static @NonNull Microcopy valued(@NonNull String key, @NonNull String arg, @Nullable Object value,
                                             @NonNull String missingKey) {
        String text = Texts.trimmedOrNull(value == null ? null : String.valueOf(value));
        return text == null ? HohenheimMicrocopy.ACCESS_RULE.of(missingKey) : HohenheimMicrocopy.ACCESS_RULE.of(key)
            .withArg(arg, text);
    }

    /**
     * How a list lets visitors in when its enabled rules are all of one plain kind: the protect-a-path method they
     * make ({@link ProtectPath#METHOD}), with how many people or networks, or which provider.
     *
     * @param method   one of the {@code ProtectPath.METHOD_*} tokens
     * @param count    the people or networks of a password or network list, else 1
     * @param provider the sign-in provider's id of a sign-in list, else null
     */
    record Way(@NonNull String method, int count, @Nullable Object provider) {
    }

    /**
     * How a list lets visitors in, read off its stored rules ({@link #wayOf(List)}).
     *
     * @return the way, or null for no list or one with no enabled rule, mixed kinds or nested groups
     */
    static @Nullable Way wayOf(@Nullable Integer listId) {
        return listId == null ? null : wayOf(Models.get(AccessRuleModel.class).findForAccessList(listId));
    }

    /**
     * THE reading of which way into a protected path a list's rules make, the one every surface naming it asks: a
     * password for some people, a few networks, one sign-in provider or one signed link.
     *
     * @param listRules every rule of one list, switched off ones included
     * @return the way, or null when the list has no enabled rule, mixes kinds or nests groups
     */
    static @Nullable Way wayOf(@NonNull List<Row> listRules) {
        List<Row> rules = listRules.stream()
            .filter(rule -> !Boolean.FALSE.equals(rule.get(AccessRuleModel.ENABLED))).toList();
        if (rules.isEmpty()) {
            return null;
        }
        int people = 0;
        int networks = 0;
        Row provider = null;
        int signed = 0;
        for (Row rule : rules) {
            String type = rule.get(AccessRuleModel.TYPE);
            switch (type == null ? "" : type) {
                case AccessRuleModel.TYPE_BASIC_AUTH -> people++;
                case AccessRuleModel.TYPE_IP_ALLOW, AccessRuleModel.TYPE_IP_DENY -> networks++;
                case AccessRuleModel.TYPE_AUTH_PROVIDER -> provider = rule;
                case AccessRuleModel.TYPE_SIGNED_LINK -> signed++;
                default -> {
                    return null;
                }
            }
        }
        if (people == rules.size()) {
            return new Way(ProtectPath.METHOD_PASSWORD, people, null);
        }
        if (networks == rules.size()) {
            return new Way(ProtectPath.METHOD_NETWORK, networks, null);
        }
        if (provider != null && rules.size() == 1) {
            return new Way(ProtectPath.METHOD_SIGN_IN, 1,
                AccessRuleModel.dataOf(provider).get(AccessRuleModel.PROVIDER_ID.getName()));
        }
        if (signed == 1 && rules.size() == 1) {
            return new Way(ProtectPath.METHOD_SIGNED_LINK, 1, null);
        }
        return null;
    }

    /**
     * How a list lets visitors in, in a few words, when its enabled rules are all of one plain kind ({@link #wayOf}).
     *
     * @return the words, or null when the list mixes kinds or nests groups (the caller then names the list)
     */
    static @Nullable Microcopy protectionOf(@Nullable Integer listId) {
        return protectionOf(wayOf(listId));
    }

    /**
     * The way's own label ({@link #wayInOf(Row)}'s words) with what it adds: how many people or networks, which
     * provider.
     *
     * @return the words, or null for no way
     */
    static @Nullable Microcopy protectionOf(@Nullable Way way) {
        if (way == null) {
            return null;
        }
        Microcopy label = CmsSupport.enumValueLabel(ProtectPath.METHOD, way.method());
        return switch (way.method()) {
            case ProtectPath.METHOD_PASSWORD -> HohenheimMicrocopy.ACCESS_RULE.of("protection_password")
                .withArg("way", label).withArg("count", way.count());
            case ProtectPath.METHOD_NETWORK -> HohenheimMicrocopy.ACCESS_RULE.of("protection_network")
                .withArg("way", label).withArg("count", way.count());
            case ProtectPath.METHOD_SIGN_IN -> HohenheimMicrocopy.ACCESS_RULE.of("protection_sign_in")
                .withArg("way", label).withArg("provider", providerName(way.provider()));
            default -> label;
        };
    }

    /**
     * THE words of how one gets in at a protected path, on its list, its read and a site's Private paths: the way's
     * own label ("With a signed link"), the list it follows by name where its rules mix ways, or "Open to everyone"
     * where its list lets every visitor through.
     */
    static @NonNull Microcopy wayInOf(@NonNull Row path) {
        return wayInOf(path, AccessListRules.ofPaths(List.of(path)));
    }

    /** {@link #wayInOf(Row)} of one path of a page, read off the page's lists and rules. */
    static @NonNull Microcopy wayInOf(@NonNull Row path, @NonNull AccessListRules read) {
        if (read.isOpen(path)) {
            return HohenheimMicrocopy.PROTECTED_PATH.of("open_to_everyone");
        }
        Integer listId = path.get(ProtectedPathModel.ACCESS_LIST_ID);
        Way way = wayOf(read.rulesOf(listId));
        return way != null ? CmsSupport.enumValueLabel(ProtectPath.METHOD, way.method())
            : HohenheimMicrocopy.APP_OVERVIEW.of("by_list").withArg("list", read.nameOf(listId));
    }

    /**
     * How a list lets visitors in, for its row in the lists: the plain words of {@link #protectionOf} when its rules
     * are one plain kind, else how many rules it holds.
     */
    static @NonNull Microcopy letsInOf(@Nullable Integer listId) {
        Microcopy plain = protectionOf(listId);
        if (plain != null) {
            return plain;
        }
        long rules = DeleteImpact.rulesOfAccessList(listId);
        return rules == 0 ? HohenheimMicrocopy.ACCESS_RULE.of("lets_in_nothing")
            : HohenheimMicrocopy.ACCESS_RULE.of("lets_in_rules").withArg("count", rules);
    }
}
