package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One stored preflight check, exactly as {@code HostPreflight.store} persisted it.
 *
 * @param label  the check in words, the stored name until the server names it (an undeclared name keeps its spelling)
 * @param detail what the check found in words, the stored text until the server words its finding (a finding this
 *               build does not declare keeps the stored text)
 * @param atIso  when THIS verdict was produced -- can be older than {@code probed_at},
 *               because the store merges; null for a pre-stamp record
 * @param fix    what an operator does to make a check that did not pass pass, null when it passed or names no
 *               check the batteries declare
 */
@HawkeyeClass
public record PreflightCheckView(
    String name,
    Microcopy label,
    String status,
    BadgeVariant statusVariant,
    boolean required,
    Microcopy detail,
    @Nullable String atIso,
    @Nullable Microcopy fix
) {

    /** Build with the pl-badge variant READ OFF the verdict as its reader is shown it, never re-spelled here. */
    public static PreflightCheckView of(String name, String status, boolean required,
                                        String detail, @Nullable String atIso) {
        BadgeVariant variant = PreflightStatus.fromToken(status).shownFor(required).badgeVariant();
        return new PreflightCheckView(name, Microcopy.literal(name), status, variant, required,
            Microcopy.literal(detail), atIso, null);
    }

    /** @return the verdict in words; a method, not a component, so it is derived again after revival */
    public Microcopy statusLabel() {
        return PreflightStatus.fromToken(this.status).shownFor(this.required).label();
    }

    /** @return whether this check did not pass, which is what puts it first and gives it a fix */
    public boolean notPassing() {
        return !PreflightStatus.fromToken(this.status).passed();
    }

    /** @return this check carrying the given fix */
    public PreflightCheckView withFix(@Nullable Microcopy fix) {
        return new PreflightCheckView(this.name, this.label, this.status, this.statusVariant, this.required,
            this.detail, this.atIso, fix);
    }

    /** @return this check carrying the given words for its name */
    public PreflightCheckView withLabel(Microcopy label) {
        return new PreflightCheckView(this.name, label, this.status, this.statusVariant, this.required, this.detail,
            this.atIso, this.fix);
    }

    /** @return this check carrying the given words for what it found */
    public PreflightCheckView withDetail(Microcopy detail) {
        return new PreflightCheckView(this.name, this.label, this.status, this.statusVariant, this.required, detail,
            this.atIso, this.fix);
    }
}
