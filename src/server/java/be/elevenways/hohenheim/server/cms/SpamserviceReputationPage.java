package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.RawValues;
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
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
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

    private final Supplier<SpamserviceClient> clientSupplier;

    public SpamserviceReputationPage() {
        this(() -> SpamserviceManager.get().client());
    }

    SpamserviceReputationPage(Supplier<SpamserviceClient> clientSupplier) {
        this.clientSupplier = clientSupplier;
    }

    @Override public @NonNull Identifier id() { return HohenheimIds.id("spamservice_reputation"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.SPAMSERVICE.of("reputation"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.SPAMSERVICE_REPUTATION; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 70; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull String standsUnder() { return HohenheimSlugs.SPAMSERVICE; }
    @Override public @NonNull Icon icon() { return Icon.of("magnifying-glass"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Conduit conduit = request.conduit();
        String ip = Texts.trimmedOrNull(conduit.getQueryParam("ip"));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", HohenheimMicrocopy.SPAMSERVICE.of("reputation")
            .resolve(conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("pageTarget", CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.SPAMSERVICE_REPUTATION));
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
                vars.put("error", HohenheimMicrocopy.SPAMSERVICE.of("disconnected")
                    .resolve(conduit.getLocales(), conduit.getMessageResolver()));
            } else {
                try {
                    ReputationDiagnostic diagnostic = client.diagnoseReputation(ip);
                    vars.put("result", Map.of("ip", diagnostic.ip(), "subnet", diagnostic.subnet()));
                    vars.put("datasets", SpamserviceRemoteStore.nameValueRows(diagnostic.datasets()));
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
        return new RenderTemplateResult(HohenheimTemplateIds.SPAMSERVICE_REPUTATION, vars);
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
        return ReputationScore.Settings.of(Zenit.SETTINGS_VALUES.getValue(
                HohenheimSettings.Security.REPUTATION_BAN_CATEGORIES),
            Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Security.REPUTATION_BAN_THRESHOLD),
            Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Security.REPUTATION_POSITIVE_EVENT_WEIGHT),
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

    private static Map<String, Object> categoryMap(Map<String, Object> values) {
        Object categories = values.get("categories");
        return RawValues.map(categories);
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
}
