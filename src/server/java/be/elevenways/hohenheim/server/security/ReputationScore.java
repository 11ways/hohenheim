package be.elevenways.hohenheim.server.security;

import be.elevenways.spamservice.client.Reputation;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable category-local weighted reputation calculation shared by enforcement and diagnostics. */
public record ReputationScore(@NonNull List<CategoryScore> categories,
                              long grossNegative,
                              long appliedPositiveCredit,
                              long net) {

    public ReputationScore {
        categories = List.copyOf(categories);
    }

    /** One category's normalized inputs and net contribution. */
    public record CategoryScore(@NonNull String category, long negative, long positive,
                                int positiveWeight, long appliedCredit, long net) {
    }

    /** Immutable normalized policy settings captured once for one evaluation. */
    public record Settings(@NonNull Set<String> categories, int threshold,
                           int positiveWeight, boolean enabled) {

        public Settings {
            categories = Collections.unmodifiableSet(new LinkedHashSet<>(categories));
            threshold = Math.max(1, threshold);
            positiveWeight = Math.max(0, positiveWeight);
            enabled = enabled && !categories.isEmpty();
        }

        public static @NonNull Settings of(@Nullable String categories,
                                           @Nullable Integer threshold,
                                           @Nullable Integer positiveWeight,
                                           boolean available) {
            return new Settings(parseCategories(categories),
                threshold != null ? threshold : 25,
                positiveWeight != null ? positiveWeight : 10,
                available);
        }
    }

    public static @NonNull ReputationScore calculate(@NonNull Reputation reputation,
                                                      @NonNull Settings settings) {
        Map<String, Long> negative = new LinkedHashMap<>();
        reputation.events().forEach((category, events) ->
            negative.put(category, events != null ? events.count() : 0));
        Map<String, Long> positive = new LinkedHashMap<>();
        reputation.positive().forEach((category, events) ->
            positive.put(category, events != null ? events.count() : 0));
        return calculate(negative, positive, settings);
    }

    public static @NonNull ReputationScore calculate(@NonNull Map<String, Long> negative,
                                                      @NonNull Map<String, Long> positive,
                                                      @NonNull Settings settings) {
        Map<String, Long> normalizedNegative = normalizeCounts(negative);
        Map<String, Long> normalizedPositive = normalizeCounts(positive);
        List<CategoryScore> categoryScores = new ArrayList<>();
        long grossNegative = 0;
        long appliedPositiveCredit = 0;
        long net = 0;

        for (String category : settings.categories()) {
            long negativeCount = normalizedNegative.getOrDefault(category, 0L);
            long positiveCount = normalizedPositive.getOrDefault(category, 0L);
            long availableCredit = saturatingMultiply(positiveCount, settings.positiveWeight());
            long contribution = availableCredit >= negativeCount ? 0 : negativeCount - availableCredit;
            long appliedCredit = negativeCount - contribution;
            categoryScores.add(new CategoryScore(category, negativeCount, positiveCount,
                settings.positiveWeight(), appliedCredit, contribution));
            grossNegative = saturatingAdd(grossNegative, negativeCount);
            appliedPositiveCredit = saturatingAdd(appliedPositiveCredit, appliedCredit);
            net = saturatingAdd(net, contribution);
        }

        return new ReputationScore(categoryScores, grossNegative, appliedPositiveCredit, net);
    }

    /** Compact summary first so BanService's 255-character persistence cap keeps the decision. */
    public @NonNull String auditReason(@NonNull Settings settings, int windowDays) {
        StringBuilder reason = new StringBuilder("spamservice reputation: net ")
            .append(this.net).append(" >= threshold ").append(settings.threshold())
            .append("; gross negative ").append(this.grossNegative)
            .append("; applied positive credit ").append(this.appliedPositiveCredit)
            .append("; window ").append(Math.max(0, windowDays)).append("d; categories: ");
        for (int i = 0; i < this.categories.size(); i++) {
            CategoryScore category = this.categories.get(i);
            if (i > 0) {
                reason.append(", ");
            }
            reason.append(category.category()).append('=').append(category.negative())
                .append("-(").append(category.positive()).append('*')
                .append(category.positiveWeight()).append(")=").append(category.net());
        }
        return reason.toString();
    }

    public boolean reaches(@NonNull Settings settings) {
        return this.net >= settings.threshold();
    }

    public static @NonNull Set<String> parseCategories(@Nullable String setting) {
        Set<String> parsed = new LinkedHashSet<>();
        if (setting != null) {
            for (String part : setting.split(",")) {
                String category = part.trim().toLowerCase(Locale.ROOT);
                if (!category.isEmpty()) {
                    parsed.add(category);
                }
            }
        }
        return parsed;
    }

    private static @NonNull Map<String, Long> normalizeCounts(@NonNull Map<String, Long> counts) {
        Map<String, Long> normalized = new LinkedHashMap<>();
        counts.forEach((rawCategory, rawCount) -> {
            if (rawCategory == null) {
                return;
            }
            String category = rawCategory.trim().toLowerCase(Locale.ROOT);
            if (category.isEmpty()) {
                return;
            }
            long count = rawCount != null ? Math.max(0, rawCount) : 0;
            normalized.merge(category, count, ReputationScore::saturatingAdd);
        });
        return normalized;
    }

    private static long saturatingMultiply(long value, int multiplier) {
        if (value <= 0 || multiplier <= 0) {
            return 0;
        }
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
