package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

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
            case AccessRuleModel.TYPE_GROUP -> ruleText(
                AccessListModel.SATISFY_ALL.equals(
                    AccessRuleModel.text(data.get(AccessRuleModel.GROUP_SATISFY.getName())))
                    ? "summary_group_all" : "summary_group_any");
            case AccessRuleModel.TYPE_IP_ALLOW, AccessRuleModel.TYPE_IP_DENY -> ruleText("summary_network")
                .withArg("network", blank(data.get(AccessRuleModel.NETWORK.getName())));
            case AccessRuleModel.TYPE_BASIC_AUTH -> ruleText("summary_basic_auth")
                .withArg("username", blank(data.get(AccessRuleModel.BASIC_AUTH_USERNAME.getName())));
            case AccessRuleModel.TYPE_AUTH_PROVIDER -> {
                String permission = AccessRuleModel.text(
                    data.get(AccessRuleModel.PROVIDER_REQUIRED_PERMISSION.getName()));
                Microcopy summary = ruleText(permission == null
                    ? "summary_auth_provider" : "summary_auth_provider_permission")
                    .withArg("provider", providerName(data.get(AccessRuleModel.PROVIDER_ID.getName())));
                yield permission == null ? summary : summary.withArg("permission", permission);
            }
            default -> ruleText("summary_unknown");
        };
    }

    /**
     * The rule's type label and its summary as one line, for the surfaces that have room
     * for neither a badge nor a second row (the record heading, the list's name cell).
     *
     * @return the resolved line, or null when no request is in scope to resolve it in
     */
    static @Nullable String titleOf(@NonNull Row rule) {
        String type = rule.get(AccessRuleModel.TYPE);
        String kind = CmsSupport.enumLabel(AccessRuleModel.TYPE, type);
        String summary = CmsSupport.resolvedText(summaryOf(rule, type));
        if (kind == null || summary == null) {
            return null;
        }
        return CmsSupport.resolvedText(ruleText("summary_line")
            .withArg("kind", kind)
            .withArg("summary", summary));
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
            ? new EnumBadgeState("on", ruleText("state_on"), "check", "success", null, true)
            : new EnumBadgeState("off", ruleText("state_off"), "xmark", "secondary", null, true);
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
        return provider != null ? blank(provider.get(SiteAuthProviderModel.NAME)) : "";
    }

    private static @NonNull String blank(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static @NonNull Microcopy ruleText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "access_rule");
    }
}
