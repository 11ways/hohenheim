package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.routing.RouteScope;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE rule for what a reader of the rendering panel may see of a failure reason the daemon or the
 * transport worded.
 *
 * AIDEV-NOTE: failure reasons across the instance tier (a release's failure_reason and step log, a
 * build's failure_reason, a snapshot's or backup's error) are stamped with the daemon's and
 * transport's OWN text, which names image registries, socket paths, host paths, instance ids and ssh
 * failures. The delegated panel is told THAT it failed, which is the fact a tenant can act on; the
 * operator reads the reason on /admin -- the InstanceOverviewPage install_error shape. The question
 * is about the SURFACE (CmsSupport.isDelegatedPanel), never about the viewer, so an operator opening
 * /manage sees exactly what a tenant sees there. Two shapes of the one rule: a stored reason read at
 * render time (of + shown) and a live exception worded at write time (operatorDetail).
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class WithheldFailure {

    private final boolean delegated;
    private final @NonNull String withheld;

    private WithheldFailure(boolean delegated, @NonNull String withheld) {
        this.delegated = delegated;
        this.withheld = withheld;
    }

    /**
     * A daemon's or transport's OWN failure text, for the operator surface only.
     *
     * AIDEV-NOTE: an exception message names image registries, socket paths, host paths and
     * peer addresses -- operator inventory. On the delegated panel this answers null and the
     * caller words a tenant-safe refusal instead (the InstanceOverviewPage install_error rule);
     * the operator still reads the reason on /admin or in the record's stored failure reason.
     * No request in scope (a task, a direct test call) is the operator lane.
     *
     * @return the failure's message (its class name when it carries none), or null on /manage
     */
    public static @Nullable String operatorDetail(@NonNull Throwable failure) {
        Conduit conduit = RouteScope.currentConduit();
        if (conduit != null && CmsSupport.isDelegatedPanel(conduit)) {
            return null;
        }
        String message = failure.getMessage();
        return message != null && !message.isBlank() ? message : failure.getClass().getSimpleName();
    }

    /** The rule for the panel this conduit is rendering. */
    static @NonNull WithheldFailure of(@NonNull Conduit conduit) {
        boolean delegated = CmsSupport.isDelegatedPanel(conduit);
        String withheld = delegated
            ? Microcopy.of("failure_withheld").withFilter("scope", "delegated")
                .resolve(conduit.getLocales(), conduit.getMessageResolver())
            : "";
        return new WithheldFailure(delegated, withheld);
    }

    /** @return whether this render withholds daemon-worded text */
    boolean delegated() {
        return this.delegated;
    }

    /** @return the reason as this panel may show it: blank stays blank, a delegated reader gets the tenant-safe sentence */
    @NonNull String shown(@Nullable String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        return this.delegated ? this.withheld : reason;
    }

    /** @return an operator-only text (a step log) as this panel may show it, null when withheld */
    @Nullable String operatorOnly(@Nullable String text) {
        return this.delegated ? null : text;
    }
}
