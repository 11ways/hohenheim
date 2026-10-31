package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.channel.ChannelLink;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.RecordCapabilityScope;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * THE admission of a channel link that OBSERVES instances: one (its live stats, its deploy log) through
 * {@link #admitted}/{@link #views}, or many (a page's live figures) through {@link #viewable}; the link's principal
 * holds {@code view} on the record, the verb the page carrying the feed is scoped by.
 *
 * AIDEV-NOTE: the channels declare only {@code requiresLogin}, because a record capability is not a permission, so each
 * handler admits here at open and again at every revalidation (the figure channel's key guard asks {@link #viewable}
 * for it); a failed revalidation cuts that link alone.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class InstanceViewLinks {

    private InstanceViewLinks() {
    }

    /**
     * @return the instance the open data names, when the link may view it
     * @throws DomainRefusal {@code PERMISSION_DENIED} for an unparseable id, a missing instance and one not the
     *                       viewer's alike: naming the difference would make the link an instance-existence oracle
     */
    public static int admitted(@NonNull ChannelLink<?, ?> link, @Nullable Object openData) {
        Integer instanceId = RawValues.parsedInt(openData);
        if (instanceId == null || !views(link, instanceId)) {
            // Typed, so the client ends the link instead of retrying a refusal no wait lifts.
            throw new DomainRefusal(ZenitRefusalReason.PERMISSION_DENIED, "Not permitted");
        }
        return instanceId;
    }

    /** @return whether the viewer is an operator, who may read the daemon's own words */
    public static boolean isOperator(@NonNull ChannelLink<?, ?> link) {
        Principal principal = link.getPrincipal();
        return principal != null && AccessContext.detached(principal).hasPermission(HohenheimSources.ADMIN_ACCESS);
    }

    /**
     * The principal-only capability walk (no conduit exists on a channel link), through the link's own face: the
     * session's principal with an anonymous one folded to null, the seam a socketless FakeChannelLink answers too.
     */
    public static boolean views(@NonNull ChannelLink<?, ?> link, int instanceId) {
        Principal principal = link.getPrincipal();
        return principal != null && HohenheimAccess.hasInstanceCapability(principal, instanceId,
            HohenheimCapabilities.VIEW);
    }

    /**
     * The set-wise face of {@link #views} for a link that observes MANY instances (a list's live figures): one
     * capability scope answers every id, so admitting N ids costs what admitting one costs.
     *
     * @return the ids the link may view, in the order asked; unknown and foreign ids alike are simply absent
     */
    public static @NonNull Set<Integer> viewable(@NonNull ChannelLink<?, ?> link, @NonNull Collection<Integer> ids) {
        Principal principal = link.getPrincipal();
        Set<Integer> viewable = new LinkedHashSet<>();
        if (principal == null || ids.isEmpty()) {
            return viewable;
        }
        RecordCapabilityScope scope = HohenheimAccess.capabilityScope(principal, InstanceModel.MODEL_ID,
            HohenheimCapabilities.VIEW);
        if (scope.isNone()) {
            return viewable;
        }
        for (Integer id : ids) {
            if (scope.isAll() || scope.recordIds().contains(String.valueOf(id))) {
                viewable.add(id);
            }
        }
        return viewable;
    }
}
