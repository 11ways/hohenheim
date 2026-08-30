package be.elevenways.hohenheim.dns;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What a delegation check found for a primary zone, from the parent's point of view: the
 * one declaring home of that vocabulary, carried on {@code dns_zones.delegation_status}.
 *
 * AIDEV-NOTE: the check reads the PARENT zone's delegation and the delegated servers
 * themselves, never our own zone data alone (the "no NS RRset" attention item already
 * covers that). Members are ordered by severity so the persisted verdict of a zone with
 * several findings is the worst one; the individual findings ride the detail column. A
 * server serving STALE data outranks a listing mismatch: the mismatch is what an operator
 * sees at the registrar, the stale answer is what a resolver gets. An
 * unknown stored token resolves to null and is treated as unchecked, never as healthy.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
public enum DelegationVerdict {

    /** The parent delegates to exactly the NS names we list and every one serves our serial. */
    MATCHES("matches", null, "circle-check", "green"),

    /**
     * The apex NS set we serve disagrees with the controller's declared nameserver set
     * ({@code dns.nameservers}): a local configuration fact, judged before the parent is.
     */
    APEX_UNDECLARED("apex_undeclared", "warning", "code-branch", "orange"),

    /**
     * The SOA MNAME we serve is not one of the apex NS names: the zone names a primary
     * nobody delegates to, and often one with no address at all. A local configuration
     * fact like {@link #APEX_UNDECLARED}, judged before the parent is.
     */
    SOA_MNAME_UNLISTED("soa_mname_unlisted", "warning", "code-branch", "orange"),

    /** The parent's nameservers could not be reached, so nothing could be judged. */
    PARENT_UNREACHABLE("parent_unreachable", "warning", "question", "gray"),

    /** The parent holds no delegation for the zone at all (the registrar step is pending). */
    NOT_DELEGATED("not_delegated", "warning", "link-slash", "orange"),

    /** We list an apex NS the parent does not delegate to. */
    LISTED_NOT_DELEGATED("listed_not_delegated", "warning", "code-branch", "orange"),

    /** The parent delegates to a nameserver our apex NS RRset does not list. */
    DELEGATED_NOT_LISTED("delegated_not_listed", "warning", "code-branch", "orange"),

    /** A delegated server answers, but with a serial behind the one this primary serves. */
    NS_STALE_SERIAL("ns_stale_serial", "warning", "hourglass-half", "orange"),

    /** An in-bailiwick nameserver is delegated without a glue address at the parent. */
    MISSING_GLUE("missing_glue", "error", "unlink", "red"),

    /** A delegated nameserver does not answer authoritatively for the zone: a lame delegation. */
    NS_UNREACHABLE("ns_unreachable", "error", "triangle-exclamation", "red");

    private final String token;
    private final @Nullable String severity;
    private final String icon;
    private final String color;

    DelegationVerdict(String token, @Nullable String severity, String icon, String color) {
        this.token = token;
        this.severity = severity;
        this.icon = icon;
        this.color = color;
    }

    /** @return the stored column value */
    public @NonNull String token() {
        return this.token;
    }

    /** @return the attention severity this verdict raises, or null when it raises none */
    public @Nullable String severity() {
        return this.severity;
    }

    public @NonNull String icon() {
        return this.icon;
    }

    public @NonNull String color() {
        return this.color;
    }

    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "dns_delegation");
    }

    /** The member behind a stored token, or null for anything this build does not declare. */
    public static @Nullable DelegationVerdict forToken(@Nullable String token) {
        for (DelegationVerdict verdict : values()) {
            if (verdict.token.equals(token)) {
                return verdict;
            }
        }
        return null;
    }

    /** @return the more severe of the two, the declaration order being the severity order */
    public @NonNull DelegationVerdict worseOf(@NonNull DelegationVerdict other) {
        return other.ordinal() > this.ordinal() ? other : this;
    }

    /**
     * The schema-field builder carrying this vocabulary, so no stored option set can drift.
     * Callers finish it with their own label/help, never with more values.
     */
    public static EnumField.@NonNull Builder fieldBuilder(@NonNull String name) {
        EnumField.Builder builder = EnumField.builder(name);
        for (DelegationVerdict verdict : values()) {
            builder.value(verdict.token(), value -> value
                .displayName(verdict.name())
                .label(verdict.label())
                .icon(verdict.icon())
                .color(verdict.color()));
        }
        return builder;
    }
}
