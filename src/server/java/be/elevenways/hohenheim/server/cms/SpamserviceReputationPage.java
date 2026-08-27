package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.security.ReputationScore;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.ReputationDiagnostic;
import be.elevenways.spamservice.client.SpamserviceApiException;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Strict reputation diagnostic with Hohenheim's weighted policy explanation. */
public final class SpamserviceReputationPage extends PanelPage {

    public static final String SLUG = "spamservice-reputation";
    private final Supplier<SpamserviceClient> clientSupplier;

    public SpamserviceReputationPage() {
        this(() -> SpamserviceManager.get().client());
    }

    SpamserviceReputationPage(Supplier<SpamserviceClient> clientSupplier) {
        this.clientSupplier = clientSupplier;
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_reputation"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("reputation").withFilter("scope", "spamservice"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 70; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("magnifying-glass"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext context) {
        String ip = Texts.trimmedOrNull(conduit.getQueryParam("ip"));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", Microcopy.of("reputation").withFilter("scope", "spamservice")
            .resolve(conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("pageTarget", CmsRoutes.list("admin", SLUG));
        vars.put("ip", ip != null ? ip : "");
        vars.put("error", "");
        vars.put("result", Map.of());
        vars.put("datasets", List.of());
        vars.put("negative", List.of());
        vars.put("positive", List.of());
        vars.put("weighted", List.of());
        ReputationScore.Settings settings = scoreSettings();
        vars.put("threshold", settings.threshold());
        vars.put("positiveWeight", settings.positiveWeight());
        vars.put("net", 0L);

        if (ip != null) {
            SpamserviceClient client = this.clientSupplier.get();
            if (client == null) {
                vars.put("error", resolve(conduit, "disconnected"));
            } else {
                try {
                    ReputationDiagnostic diagnostic = client.diagnoseReputation(ip);
                    vars.put("result", Map.of("ip", diagnostic.ip(), "subnet", diagnostic.subnet()));
                    vars.put("datasets", entries(diagnostic.datasets()));
                    vars.put("negative", categories(diagnostic.events()));
                    vars.put("positive", categories(diagnostic.positive()));
                    ReputationScore score = weighted(diagnostic, settings);
                    vars.put("weighted", weightedRows(score));
                    vars.put("net", score.net());
                } catch (SpamserviceApiException failure) {
                    vars.put("error", failure.getMessage());
                }
            }
        }
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/spamservice-reputation"), vars);
    }

    private static ReputationScore weighted(ReputationDiagnostic diagnostic,
                                            ReputationScore.Settings settings) {
        return ReputationScore.calculate(counts(categoryMap(diagnostic.events())),
            counts(categoryMap(diagnostic.positive())), settings);
    }

    private static List<Map<String, Object>> weightedRows(ReputationScore score) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReputationScore.CategoryScore category : score.categories()) {
            rows.add(Map.of("category", category.category(), "negative", category.negative(),
                "positive", category.positive(), "weight", category.positiveWeight(),
                "credit", category.appliedCredit(), "net", category.net()));
        }
        return rows;
    }

    private static ReputationScore.Settings scoreSettings() {
        return ReputationScore.Settings.of(HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Security.REPUTATION_BAN_CATEGORIES),
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.REPUTATION_BAN_THRESHOLD),
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.REPUTATION_POSITIVE_EVENT_WEIGHT),
            true);
    }

    private static List<Map<String, Object>> categories(Map<String, Object> values) {
        return categoryMap(values).entrySet().stream().map(entry -> {
            Object value = entry.getValue();
            long count = count(value);
            Object lastAt = value instanceof Map<?, ?> map ? map.get("last_at") : null;
            return Map.<String, Object>of("category", entry.getKey(), "count", count,
                "lastAt", lastAt != null ? String.valueOf(lastAt) : "");
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> categoryMap(Map<String, Object> values) {
        Object categories = values.get("categories");
        return categories instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static long count(Object value) {
        if (!(value instanceof Map<?, ?> map)) return 0;
        Object count = map.get("count");
        return count instanceof Number number ? number.longValue() : 0;
    }

    private static Map<String, Long> counts(Map<String, Object> values) {
        Map<String, Long> result = new LinkedHashMap<>();
        values.forEach((category, value) -> result.put(category, count(value)));
        return result;
    }

    private static List<Map<String, Object>> entries(Map<String, Object> values) {
        return values.entrySet().stream().map(entry -> Map.<String, Object>of(
            "name", entry.getKey(), "value", String.valueOf(entry.getValue()))).toList();
    }

    private static String resolve(Conduit conduit, String key) {
        return Microcopy.of(key).withFilter("scope", "spamservice")
            .resolve(conduit.getLocales(), conduit.getMessageResolver());
    }
}
