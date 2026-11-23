package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.cluster.ClusterEndpoints;
import be.elevenways.hohenheim.cluster.JoinRefusal;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.CallerChannel;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.StoredRecordPrincipal;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Answers the three calls of ClusterEndpoints: join (the code is the credential), status and leave (the link key is).
 *
 * AIDEV-NOTE: an unknown key answers 401, naming nothing. A join's refusal answers its JoinRefusal token: 429 for a
 * spent failure budget, 409 for a server that cannot get a master or is linking another at this moment, 422
 * otherwise. The code is the gate of a join, so its write is system work; a leave runs as the linked server its key
 * names, which holds no permission.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ClusterHandlers {

    private ClusterHandlers() {
    }

    /** Installs the handlers of the three cluster calls. */
    public static void init() {
        ClusterEndpoints.JOIN.setHandler(ClusterHandlers::join);
        ClusterEndpoints.STATUS.setHandler(conduit -> {
            if (LinkedServers.byKey(keyOf(conduit)) == null) {
                return unknownKey(conduit);
            }
            return new JsonResult<>(LinkedServers.ownStatus());
        });
        ClusterEndpoints.LEAVE.setHandler(conduit -> {
            Row row = LinkedServers.byKey(keyOf(conduit));
            Principal asker = row == null ? null : StoredRecordPrincipal.resolve(LinkedServers.principalOf(row));
            if (row == null || asker == null) {
                return unknownKey(conduit);
            }
            ExecutionIdentity.run(ExecutionIdentity.caller(AccessContext.detached(asker),
                CallerChannel.ofRequest(conduit)), () -> LinkedServers.left(row));
            return new JsonResult<>(Map.of("left", true));
        });
    }

    private static @Nullable ActionResult<Object> join(@NonNull Conduit conduit) {
        Map<String, Object> body = bodyOf(conduit);
        if (body == null) {
            return refused(conduit, JoinRefusal.UNREADABLE);
        }
        String scope = "address:" + conduit.getRemoteIp();
        LinkedServers.Joined joined = ExecutionIdentity.supplyAsSystem(HohenheimSystemPurposes.CLUSTER_JOIN,
            () -> LinkedServers.acceptJoin(body, scope));
        return switch (joined) {
            case LinkedServers.Accepted accepted -> new JsonResult<>(accepted.answer());
            case LinkedServers.Refused refused -> refused(conduit, refused.refusal());
        };
    }

    private static @NonNull ActionResult<Object> refused(@NonNull Conduit conduit, @NonNull JoinRefusal refusal) {
        conduit.setResponseStatus(switch (refusal) {
            case TOO_MANY_TRIES -> 429;
            case HAS_MASTER, MANAGES_OTHERS, BUSY -> 409;
            case NO_SUCH_CODE, EXPIRED, UNREADABLE -> 422;
        });
        return new JsonResult<>(Map.of("refusal", refusal.token()));
    }

    private static @NonNull ActionResult<Object> unknownKey(@NonNull Conduit conduit) {
        conduit.setResponseStatus(401);
        return new JsonResult<>(Map.of("error", "unknown key"));
    }

    /** @return the bearer key of the request, null without one */
    private static @Nullable String keyOf(@NonNull Conduit conduit) {
        String header = conduit.getRequestHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).trim();
    }

    /** @return the request's JSON object, null when it carries none */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> bodyOf(@NonNull Conduit conduit) {
        try {
            Object parsed = Zenit.DRY.parseJsonTree(conduit.getBodyAsString());
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
