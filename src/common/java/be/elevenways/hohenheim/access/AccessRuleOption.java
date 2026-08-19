package be.elevenways.hohenheim.access;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One choice in the Rules tab's add form: a rule type, or a group to add into (whose
 * value is empty for the list's implicit root group).
 */
@HawkeyeClass
public record AccessRuleOption(@NonNull String value, @NonNull Microcopy label) {
}
