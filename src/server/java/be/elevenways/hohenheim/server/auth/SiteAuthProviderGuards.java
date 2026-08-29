package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A site auth provider still named by a live site or by an access rule refuses to go, on
 * every delete lane.
 *
 * AIDEV-NOTE: the consequence of a dangling reference is an OUTAGE, not an exposure --
 * {@code SiteDispatcher} builds {@code FAIL_CLOSED_GATE} for a site whose provider cannot
 * be built and {@code AccessRuleTree} compiles a provider leaf whose provider is gone to
 * an {@code UnknownNode}, so every request to that site is refused rather than let
 * through. Refusing the delete is still right: the operator would otherwise take a site
 * offline from a catalog page that never mentioned it, with the failure surfacing only at
 * the next route load. The refusal is declared here so a criteria delete and a direct
 * save meet it too; {@code AuthProviderResource} offers the same fact as a dead delete.
 *
 * AIDEV-NOTE: a rule names its provider inside the type-specific {@code data} JSON, which
 * no backend correlates portably, so the rule half reads the provider-typed rules (a
 * handful) and asks the pending delete's OWN criteria whether any doomed row is among
 * the ids they name -- the dependents are read, the doomed rows never are.
 */
public final class SiteAuthProviderGuards {

    private static volatile boolean installed;

    private SiteAuthProviderGuards() {
    }

    /** Install the provider hook; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        SiteAuthProviderModel.SCHEMA.addBeforeRemoveHook(SiteAuthProviderGuards::refuseWhileReferenced);
    }

    /** @throws Violations {@code auth_provider_in_use} naming the provider, a site and the counts */
    private static void refuseWhileReferenced(@NonNull RemoveFromDatasource context) {
        QueryBuilder<Row> sites = liveSites()
            .where(PendingDeletes.dependents(SiteModel.AUTH_PROVIDER, context));
        long siteCount = sites.count();
        long ruleCount = 0;
        Row doomedByRule = null;
        Set<Integer> named = providerIdsNamedByRules();
        if (!named.isEmpty()) {
            QueryBuilder<Row> doomedProviders = Models.get(SiteAuthProviderModel.class).find()
                .where(SiteAuthProviderModel.ID.in(named));
            Criteria doomed = PendingDeletes.criteria(context);
            if (doomed != null) {
                doomedProviders.where(doomed);
            }
            doomedByRule = doomedProviders.first();
            if (doomedByRule != null) {
                ruleCount = rulesNaming(doomedByRule.get(SiteAuthProviderModel.ID));
            }
        }
        if (siteCount == 0 && ruleCount == 0) {
            return;
        }
        Row firstSite = siteCount > 0 ? sites.first() : null;
        Row provider = firstSite != null ? firstSite.get(SiteModel.AUTH_PROVIDER) : doomedByRule;
        throw Violations.ofForm(CmsSupport.violationText("auth_provider_in_use")
            .withArg("name", provider != null
                ? String.valueOf((Object) provider.get(SiteAuthProviderModel.NAME)) : "")
            .withArg("sites", siteCount)
            .withArg("rules", ruleCount));
    }

    /** The provider ids every provider-typed access rule names, dangling ones included. */
    public static @NonNull Set<Integer> providerIdsNamedByRules() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Row rule : providerRules().all()) {
            Integer id = providerIdOf(rule);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** @return how many access rules name this provider */
    public static long rulesNaming(@Nullable Integer providerId) {
        if (providerId == null) {
            return 0;
        }
        long count = 0;
        for (Row rule : providerRules().all()) {
            if (providerId.equals(providerIdOf(rule))) {
                count++;
            }
        }
        return count;
    }

    /** @return the provider a rule's data names, or null when absent or unreadable */
    public static @Nullable Integer providerIdOf(@NonNull Row rule) {
        Object raw = AccessRuleModel.dataOf(rule).get(AccessRuleModel.PROVIDER_ID.getName());
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String text = AccessRuleModel.text(raw);
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }

    private static @NonNull QueryBuilder<Row> providerRules() {
        return Models.get(AccessRuleModel.class).find()
            .where(AccessRuleModel.TYPE.eq(AccessRuleModel.TYPE_AUTH_PROVIDER));
    }

    private static @NonNull QueryBuilder<Row> liveSites() {
        return Models.get(SiteModel.class).find().where(SiteModel.DELETED_AT.isNull());
    }
}
