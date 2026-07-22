package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DeploymentModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.source.GitSiteRequestHandler;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deployments tab on a git-sourced site: deploy history with captured build
 * logs, plus deploy-now / cancel / rollback controls.
 */
public final class SiteDeploymentsPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_deployments"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("deployments").withFilter("scope", "site"); }
    @Override public @NonNull String slug() { return "deployments"; }
    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }

    @Override
    public boolean visibleFor(@NonNull Row site) {
        return SiteModel.SOURCE_GIT.equals(site.get(SiteModel.SOURCE));
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row site) {
        Integer siteId = site.get(SiteModel.ID);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", site.get(SiteModel.NAME) + " - Deployments");
        vars.put("siteId", siteId);
        vars.put("siteName", site.get(SiteModel.NAME));

        GitSiteRequestHandler git = findGitHandler(siteId);
        vars.put("isDeploying", git != null && git.isDeploying());
        vars.put("canRollback", git != null && git.hasPreviousSlot() && !git.isDeploying());
        vars.put("currentCommit", git != null && git.getCurrentCommit() != null
            ? shortSha(git.getCurrentCommit()) : "");

        List<Map<String, Object>> deployments = new ArrayList<>();
        for (Row row : Models.get(DeploymentModel.class).findBySiteId(siteId, 50)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", row.get(DeploymentModel.ID));
            entry.put("status", orEmpty(row.get(DeploymentModel.STATUS)));
            entry.put("statusVariant", statusVariant(row.get(DeploymentModel.STATUS)));
            entry.put("reason", orEmpty(row.get(DeploymentModel.REASON)));
            entry.put("commit", shortSha(row.get(DeploymentModel.COMMIT_SHA)));
            entry.put("duration", durationLabel(row.get(DeploymentModel.DURATION_MS)));
            entry.put("error", orEmpty(row.get(DeploymentModel.ERROR)));
            Instant startedAt = row.get(DeploymentModel.STARTED_AT);
            entry.put("startedAtIso", startedAt != null ? startedAt.toString() : "");
            String log = row.get(DeploymentModel.LOG);
            entry.put("log", log != null ? log : "");
            entry.put("hasLog", log != null && !log.isBlank());
            deployments.add(entry);
        }
        vars.put("deployments", deployments);

        if (HohenheimAccess.isAdmin(accessContext)) {
            putWebhookVars(vars, site);
        }

        vars.put("panelSlug", CmsSupport.panelSlug(conduit));
        vars.put("recordTabs", recordTabs(conduit));
        vars.put("timeWording", RelativeTimeWording.resolve(
            conduit.getLocales(), conduit.getMessageResolver()));

        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/site-deployments"), vars);
    }

    /**
     * The push URL + secret an operator pastes into their git host. The
     * webhook endpoint is intercepted before hostname routing, so any
     * hostname pointing at the proxy works; the site's own first exact
     * domain is the copy-pastable choice.
     */
    private static void putWebhookVars(Map<String, Object> vars, Row site) {
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = site.get(SiteModel.SOURCE_SETTINGS) instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
        vars.put("webhookSecret", orEmpty(settings.get(GitSourceSchema.WEBHOOK_SECRET.getName())));
        vars.put("webhookAutoDeploy",
            Boolean.TRUE.equals(settings.get(GitSourceSchema.AUTO_DEPLOY.getName())));

        String path = "/api/webhooks/git/" + orEmpty(site.get(SiteModel.SLUG));
        String url = path;
        Row domain = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(site.get(SiteModel.ID)))
            .where(SiteDomainModel.MATCH_TYPE.eq(SiteDomainModel.MATCH_EXACT))
            .first();
        if (domain != null) {
            String scheme = Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL)) ? "https" : "http";
            url = scheme + "://" + domain.get(SiteDomainModel.HOSTNAME) + path;
        }
        vars.put("webhookUrl", url);
    }

    private static @Nullable GitSiteRequestHandler findGitHandler(Integer siteId) {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null && siteId != null
            && proxy.getDispatcher().findHandlerBySiteId(siteId) instanceof GitSiteRequestHandler git) {
            return git;
        }
        return null;
    }

    private static String statusVariant(Object status) {
        return switch (String.valueOf(status)) {
            case DeploymentModel.STATUS_SUCCESS -> "default";
            case DeploymentModel.STATUS_RUNNING -> "secondary";
            case DeploymentModel.STATUS_CANCELLED -> "outline";
            default -> "destructive";
        };
    }

    private static String shortSha(Object sha) {
        String value = sha != null ? String.valueOf(sha) : "";
        return value.length() > 8 ? value.substring(0, 8) : value;
    }

    private static String durationLabel(Object durationMs) {
        if (!(durationMs instanceof Integer ms)) {
            return "";
        }
        if (ms < 1000) {
            return ms + " ms";
        }
        return String.format("%.1f s", ms / 1000.0);
    }

    private static String orEmpty(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
