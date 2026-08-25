package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import be.elevenways.zenit.common.edit.EmptyNarrowingReason;
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
 * A resolver that can say WHY it legitimately narrows to nothing also implements
 * {@link EmptyNarrowingReason}, so the empty picker explains itself instead of reading
 * as a broken list; the explanation is derived from the same sibling values and the same
 * branches the narrowing is.
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

    /**
     * A RESOLVED narrowing no record can satisfy, for a dependency that is settled and
     * legitimately admits nothing.
     *
     * AIDEV-NOTE: spelled as a contradiction on one boolean column because {@code RuleGroup}
     * has no match-none literal ({@code RuleCompiler} says so in as many words: an empty
     * group is match-ALL and is dropped). Both halves are ordinary rules, so it compiles
     * and answers identically on all eight backends. A first-class match-none belongs in
     * zenit's rule vocabulary; until it exists this is the portable spelling.
     */
    private static final RuleGroup NOTHING_QUALIFIES = RuleGroup.and(
        Rule.of("enabled", RuleOperator.IS_TRUE),
        Rule.of("enabled", RuleOperator.IS_FALSE));

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
    ) implements SiblingRulesResolver, EmptyNarrowingReason {

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

        /**
         * AIDEV-NOTE: the two branches are the two RULES above, and they must stay that
         * way: a reason naming something the narrowing does not test (host ADMISSION is
         * the tempting one -- it refuses at placement, not here) sends an operator to fix
         * a fact that would not have widened this list. The runtime tokens are
         * interpolated raw, the same spelling {@code InstanceKinds.runtimeMismatch}
         * already shows an operator.
         */
        @Override
        public @Nullable Microcopy reasonNothingQualifies(@NonNull Map<String, Object> siblingValues) {
            String kind = chosen(siblingValues, this.kindSibling);
            if (kind == null) {
                return null;
            }
            List<String> runtimes = this.runtimesByKind.get(kind);
            if (runtimes == null || runtimes.isEmpty()) {
                return null;
            }
            String key = this.volumeQuotaKinds.contains(kind)
                ? "no_quota_capable_host"
                : "no_runtime_host";
            return Microcopy.of(key).withFilter("scope", "instance")
                .withArg("runtimes", String.join(", ", runtimes));
        }
    }

    /**
     * Narrow to records whose {@code fieldName} equals the sibling's chosen value.
     *
     * AIDEV-NOTE: deliberately NO {@link EmptyNarrowingReason}. This one carries two
     * NAMES and no domain knowledge, so the only sentence it could write is a restatement
     * that the list is empty -- which is what the generic empty text already says, and
     * which the interface exists to replace. A caller that knows what its narrowing means
     * should declare its own resolver rather than teach this one to guess.
     */
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
    ) implements SiblingRulesResolver, EmptyNarrowingReason {

        /**
         * AIDEV-NOTE: a kind that does NOT run inside an image resolves to
         * {@code NOTHING_QUALIFIES} rather than to null. Null is the "the sibling is not
         * chosen yet" state, which renders the picker disabled under the framework's own
         * "choose the kind first" placeholder -- a sentence that is simply FALSE once a
         * kind IS chosen, and which was what an operator saw after picking Docker
         * container. Resolving instead lets the declared reason below say the true
         * precondition: runtime images belong to the kinds that run inside one.
         */
        @Override
        public @Nullable RuleGroup resolve(@NonNull Map<String, Object> siblingValues) {
            String kind = chosen(siblingValues, this.kindSibling);
            if (kind == null) {
                return null;
            }
            if (!this.imageKinds.contains(kind)) {
                return NOTHING_QUALIFIES;
            }
            RuleGroup.Builder rules = RuleGroup.builder(Combinator.AND)
                .add(Rule.of("enabled", RuleOperator.IS_TRUE));
            if (this.incusOnlyKinds.contains(kind)) {
                rules.add(Rule.of("incus_image", RuleOperator.IS_NOT_EMPTY));
            }
            return rules.build();
        }

        /**
         * Three branches, one per rule the resolve above can narrow with: the kind reads
         * no image at all, the Incus-only kinds fail on the variant rule, and everything
         * else fails on the enabled rule.
         */
        @Override
        public @Nullable Microcopy reasonNothingQualifies(@NonNull Map<String, Object> siblingValues) {
            String kind = chosen(siblingValues, this.kindSibling);
            if (kind == null) {
                return null;
            }
            String key;
            if (!this.imageKinds.contains(kind)) {
                key = "runtime_image_not_applicable";
            } else if (this.incusOnlyKinds.contains(kind)) {
                key = "no_incus_runtime_image";
            } else {
                key = "no_enabled_runtime_image";
            }
            return Microcopy.of(key).withFilter("scope", "instance");
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
    ) implements SiblingRulesResolver, EmptyNarrowingReason {

        /**
         * AIDEV-NOTE: a chosen NON-instance upstream resolves to {@code NOTHING_QUALIFIES}
         * rather than null -- the RuntimeImageRules stance. Null renders the disabled picker
         * under "choose the upstream first", a sentence that is FALSE once an upstream IS
         * chosen (the QA "Choose upstream_kind first on a static site" finding); resolving
         * lets the declared reason below say the true precondition instead.
         */
        @Override
        public @Nullable RuleGroup resolve(@NonNull Map<String, Object> siblingValues) {
            String kind = chosen(siblingValues, this.kindSibling);
            if (kind == null) {
                return null;
            }
            if (!this.instanceKindValue.equals(kind)) {
                return NOTHING_QUALIFIES;
            }
            return RuleGroup.and(
                Rule.list("kind", RuleOperator.IN, List.copyOf(this.exposableKinds)));
        }

        /**
         * Two branches, one per way the resolve above narrows: the chosen upstream does not
         * serve an instance at all, else nothing this installation runs publishes a port.
         */
        @Override
        public @Nullable Microcopy reasonNothingQualifies(@NonNull Map<String, Object> siblingValues) {
            String kind = chosen(siblingValues, this.kindSibling);
            if (kind == null) {
                return null;
            }
            String key = this.instanceKindValue.equals(kind)
                ? "no_exposable_instance"
                : "instance_upstream_not_applicable";
            return Microcopy.of(key).withFilter("scope", "site");
        }
    }
}
