package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import be.elevenways.zenit.common.edit.SiblingRulesResolver;
import be.elevenways.zenit.common.orm.query.rules.Combinator;
import be.elevenways.zenit.common.orm.query.rules.Rule;
import be.elevenways.zenit.common.orm.query.rules.RuleGroup;
import be.elevenways.zenit.common.orm.query.rules.RuleOperator;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The dependent-picker resolvers of the admin forms (host by kind, template by kind,
 * runtime image by kind, exposable instance by upstream kind), each a DRY value type
 * the browser re-runs on every form-scope publish.
 *
 * AIDEV-NOTE: the records are DECLARED and touched here (common) but the instances the
 * forms use are built SERVER-side with handler-derived data ({@code InstanceKinds}):
 * the kind facts live on the server handlers, and the browser-side registry has no
 * entries, so each resolver CARRIES its mapping as components. The {@code LOADED}
 * touch is what keeps the DRY revivers in the TeaVM bundle -- a record only ever
 * constructed server-side is dead code to the browser and its reviver is eliminated
 * (the DrpHostFixture lesson).
 */
@ZenitAutoLoad
public final class HohenheimPickRules {

    /** Sentinel; constructing each record from common code defeats TeaVM DCE. */
    public static final boolean LOADED = touch();

    private HohenheimPickRules() {}

    private static boolean touch() {
        if (new KindHostRules("k", Map.of(), List.of()) == null
                || new SiblingEqualsRules("k", "f") == null
                || new RuntimeImageRules("k", List.of(), List.of()) == null
                || new UpstreamInstanceRules("k", "v", List.of()) == null) {
            throw new IllegalStateException("pick-rules resolvers failed to initialise");
        }
        return true;
    }

    /** @return the sibling's raw value as trimmed text, or null when nothing is chosen */
    private static @Nullable String chosen(@NonNull Map<String, Object> siblings,
                                           @NonNull String name) {
        Object value = siblings.get(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Hosts that can carry the chosen kind: runtime in the kind's declared set, plus a
     * quota-capable volume backend for the kinds that demand one. An unknown kind
     * narrows to nothing resolvable (fail closed).
     *
     * AIDEV-NOTE: the backend narrowing reads {@link VolumeBackend#quotaCapableTokens},
     * never a spelled-out token comparison. It used to say {@code volume_backend != none},
     * which offered every ZFS and XFS-prjquota host -- backends this build cannot drive --
     * and the first deploy then refused. The picker must narrow on the SAME fact
     * {@code VolumeBackends.requireQuotaCapableHost} refuses on, or the two disagree again.
     */
    @HawkeyeClass
    public record KindHostRules(
        @NonNull String kindSibling,
        @NonNull Map<String, List<String>> runtimesByKind,
        @NonNull List<String> volumeQuotaKinds
    ) implements SiblingRulesResolver {

        @Override
        public @Nullable RuleGroup resolve(@NonNull Map<String, Object> siblingValues) {
            String kind = chosen(siblingValues, this.kindSibling);
            if (kind == null) {
                return null;
            }
            List<String> runtimes = this.runtimesByKind.get(kind);
            if (runtimes == null || runtimes.isEmpty()) {
                return null;
            }
            RuleGroup.Builder rules = RuleGroup.builder(Combinator.AND)
                .add(Rule.list("runtime", RuleOperator.IN, List.copyOf(runtimes)));
            if (this.volumeQuotaKinds.contains(kind)) {
                rules.add(Rule.list("volume_backend", RuleOperator.IN,
                    VolumeBackend.quotaCapableTokens()));
            }
            return rules.build();
        }
    }

    /** Narrow to records whose {@code fieldName} equals the sibling's chosen value. */
    @HawkeyeClass
    public record SiblingEqualsRules(
        @NonNull String siblingName,
        @NonNull String fieldName
    ) implements SiblingRulesResolver {

        @Override
        public @Nullable RuleGroup resolve(@NonNull Map<String, Object> siblingValues) {
            String value = chosen(siblingValues, this.siblingName);
            if (value == null) {
                return null;
            }
            return RuleGroup.and(Rule.of(this.fieldName, RuleOperator.EQUALS, value));
        }
    }

    /**
     * Enabled runtime images for a kind that runs inside one; a kind that never reads
     * the column keeps the picker unresolved (disabled) instead of offering a choice
     * the deploy would ignore. Incus-only kinds additionally demand an Incus variant.
     */
    @HawkeyeClass
    public record RuntimeImageRules(
        @NonNull String kindSibling,
        @NonNull List<String> imageKinds,
        @NonNull List<String> incusOnlyKinds
    ) implements SiblingRulesResolver {

        @Override
        public @Nullable RuleGroup resolve(@NonNull Map<String, Object> siblingValues) {
            String kind = chosen(siblingValues, this.kindSibling);
            if (kind == null || !this.imageKinds.contains(kind)) {
                return null;
            }
            RuleGroup.Builder rules = RuleGroup.builder(Combinator.AND)
                .add(Rule.of("enabled", RuleOperator.IS_TRUE));
            if (this.incusOnlyKinds.contains(kind)) {
                rules.add(Rule.of("incus_image", RuleOperator.IS_NOT_EMPTY));
            }
            return rules.build();
        }
    }

    /**
     * Instances a site can serve from, offered only while the chosen upstream kind is
     * the instance one: kinds whose serving container publishes a loopback port.
     */
    @HawkeyeClass
    public record UpstreamInstanceRules(
        @NonNull String kindSibling,
        @NonNull String instanceKindValue,
        @NonNull List<String> exposableKinds
    ) implements SiblingRulesResolver {

        @Override
        public @Nullable RuleGroup resolve(@NonNull Map<String, Object> siblingValues) {
            String kind = chosen(siblingValues, this.kindSibling);
            if (kind == null || !this.instanceKindValue.equals(kind)) {
                return null;
            }
            return RuleGroup.and(
                Rule.list("kind", RuleOperator.IN, List.copyOf(this.exposableKinds)));
        }
    }
}
