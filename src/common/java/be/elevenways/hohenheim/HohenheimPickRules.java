package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.typed.rule.Condition;
import be.elevenways.protoblast.common.typed.rule.Operand;
import be.elevenways.zenit.common.edit.EmptyNarrowingReason;
import be.elevenways.zenit.common.edit.SiblingRulesResolver;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dependent-picker resolvers of the admin forms (template by kind, runtime image by kind, exposable instance by
 * upstream kind, environment by project), each a DRY value type the browser re-runs on every form-scope publish.
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
@BlastAutoLoad
public final class HohenheimPickRules {

    /** Sentinel; constructing each record from common code defeats TeaVM DCE. */
    public static final boolean LOADED = touch();

    private HohenheimPickRules() {}

    private static boolean touch() {
        if (new SiblingEqualsRules("k", "f") == null
                || new RuntimeImageRules("k", List.of(), List.of()) == null
                || new UpstreamInstanceRules("k", "v", List.of()) == null
                || new EnvironmentProjectRules("p") == null) {
            throw new IllegalStateException("pick-rules resolvers failed to initialise");
        }
        return true;
    }

    /**
     * A RESOLVED narrowing no record can satisfy, for a dependency that is settled and
     * legitimately admits nothing.
     *
     * AIDEV-NOTE: the declared match-none node, never a contradiction on a column. A
     * contradiction has to NAME a variable, and a variable the target record source does
     * not project is outside that source's vocabulary -- so the old {@code enabled} hack
     * 400'd the very pickers it was meant to empty (the manage-panel InstanceModel source
     * projects name and kind only). This node names nothing and validates everywhere.
     */
    private static final Condition NOTHING_QUALIFIES = Condition.NOTHING;

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
        public @Nullable Condition resolve(@NonNull Map<String, Object> siblingValues) {
            String value = chosen(siblingValues, this.siblingName);
            if (value == null) {
                return null;
            }
            return Condition.all(Condition.test(this.fieldName, CoreTypes.EQUALS, Operand.of(value)));
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
        public @Nullable Condition resolve(@NonNull Map<String, Object> siblingValues) {
            String kind = chosen(siblingValues, this.kindSibling);
            if (kind == null) {
                return null;
            }
            if (!this.imageKinds.contains(kind)) {
                return NOTHING_QUALIFIES;
            }
            List<Condition> rules = new ArrayList<>();
            rules.add(Condition.test("enabled", CoreTypes.IS_TRUE));
            if (this.incusOnlyKinds.contains(kind)) {
                rules.add(Condition.test("incus_image", CoreTypes.IS_NOT_EMPTY));
            }
            return Condition.all(rules);
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
            return HohenheimMicrocopy.INSTANCE.of(key);
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
        public @Nullable Condition resolve(@NonNull Map<String, Object> siblingValues) {
            String kind = chosen(siblingValues, this.kindSibling);
            if (kind == null) {
                return null;
            }
            if (!this.instanceKindValue.equals(kind)) {
                return NOTHING_QUALIFIES;
            }
            return Condition.all(
                Condition.test("kind", CoreTypes.IN, Operand.of(List.copyOf(this.exposableKinds))));
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
            return HohenheimMicrocopy.SITE.of(key);
        }
    }

    /**
     * The environments of the project chosen beside the pick, or no narrowing at all while none is chosen.
     *
     * AIDEV-NOTE: an unchosen project narrows NOTHING (never null, never NOTHING_QUALIFIES): the project entry is
     * create-only, and an absent sibling cannot be told apart from a cleared one, so any other answer would disable or
     * empty the edit form's environment pick. The edit context's rule (InstanceParts.environmentRules) is what decides
     * there, and on a create without a project.
     */
    @HawkeyeClass
    public record EnvironmentProjectRules(
        @NonNull String projectSibling
    ) implements SiblingRulesResolver, EmptyNarrowingReason {

        @Override
        public @Nullable Condition resolve(@NonNull Map<String, Object> siblingValues) {
            String project = chosen(siblingValues, this.projectSibling);
            if (project == null) {
                return Condition.EVERYTHING;
            }
            return Condition.all(Condition.test("project_id", CoreTypes.EQUALS, Operand.of(project)));
        }

        /** One branch: a chosen project that has no environment yet; an unchosen one leaves the context's reason. */
        @Override
        public @Nullable Microcopy reasonNothingQualifies(@NonNull Map<String, Object> siblingValues) {
            return chosen(siblingValues, this.projectSibling) == null ? null
                : HohenheimMicrocopy.INSTANCE.of("environment_empty_chosen_project");
        }
    }
}
