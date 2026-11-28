package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.cluster.ClusterEndpoints;
import be.elevenways.hohenheim.cluster.JoinRefusal;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.server.cms.AppStates;
import be.elevenways.hohenheim.server.cms.CertificateStates;
import be.elevenways.hohenheim.server.cms.SiteStates;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.code.CodeBlock;
import be.elevenways.zenit.common.orm.code.CodeWant;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.peer.SharedRowsReader;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.CallerChannel;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.security.PermissionChecker;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.StoredRecordPrincipal;
import be.elevenways.zenit.server.operation.CarriedOperations;
import be.elevenways.zenit.server.peer.SharedRow;
import be.elevenways.zenit.server.peer.SharedRows;
import be.elevenways.zenit.server.peer.SharedRowsPage;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers the calls of ClusterEndpoints: join (the code is the credential), and every other call (the link key is).
 *
 * AIDEV-NOTE: an unknown key answers 401, naming nothing. A join's refusal answers its JoinRefusal token: 429 for a
 * spent failure budget, 409 for a server that cannot get a master or is linking another at this moment, 422
 * otherwise. The code is the gate of a join, so its write is system work; a leave runs as the linked server its key
 * names, which holds no permission.
 *
 * AIDEV-NOTE: generations and rows hand out every secret of the shared kinds, so they answer the linked server whose
 * row says it is THIS server's master and nobody else: no key, an API key or any other bearer that is no link key,
 * and a wrong key are 401 (only a link key's digest is ever looked up); a server this one manages, presenting its own
 * valid key, is 403. The answer is read as that master (a LinkedInstallPrincipal of the one SharedRowsReader declared
 * here), and core refuses every other principal again. "changed" and "codes" are the opposite way: only a server
 * this one manages may nudge it or be granted code numbers, or at joining the server a valid ticket of this one
 * names. A refusal answers the reason's code alone, never its diagnostic, and nothing of a row is ever logged.
 *
 * AIDEV-NOTE: "call" is the one call that speaks for a person, so it answers this server's master alone, as
 * generations and rows do (401, then 403 for a server this one manages); the call runs on the master's word as the
 * person it names (ClusterCalls). A body that cannot be read is 400, a surface or a call this build does not
 * serve 404, and scopes not admitting the surface 403. "run" is the opposite way, like "changed": only a server this
 * one manages tells it a state of a run, and only of a run carried to that server (404 for any other).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ClusterHandlers {

    private ClusterHandlers() {
    }

    /** The one reader of shared rows in this process, declared at its first use whatever installs the handlers. */
    private static final class Reader {
        static final SharedRowsReader READER = SharedRowsReader.declare(ClusterHandlers.class);
    }

    /**
     * Installs the handlers of the cluster calls, the nudge that tells this server's master of a change, the
     * numbering that takes code numbers from it, the carrier of tool calls about its managed servers' things and the
     * state of this server's apps, sites, addresses and certificates it offers its master.
     */
    public static void init() {
        ClusterNudge.install();
        ClusterCodes.install();
        ClusterCalls.install();
        AppStates.declare();
        SiteStates.declare();
        CertificateStates.declare();
        ClusterEndpoints.GENERATIONS.setHandler(conduit -> asMaster(conduit, (master, caller) -> {
            Map<String, Object> generations = new LinkedHashMap<>();
            SharedRows.generations(caller)
                .forEach((model, generation) -> generations.put(model.toString(), generation));
            return new JsonResult<>(Map.of("generations", generations));
        }));
        ClusterEndpoints.ROWS.setHandler(conduit -> asMaster(conduit, (master, caller) -> {
            Map<String, Object> body = bodyOf(conduit);
            Identifier model = body != null && body.get("model") instanceof String id ? Identifier.tryParse(id) : null;
            if (model == null || !(body.get("limit") instanceof Number limit) || limit.longValue() < 1
                    || limit.longValue() > SharedRows.MAX_PAGE) {
                return refusal(conduit, ZenitRefusalReason.BAD_REQUEST);
            }
            String after = body.get("after") instanceof String cursor ? cursor : null;
            SharedRowsPage page = SharedRows.rows(caller, model, after, (int) limit.longValue());
            List<Map<String, Object>> rows = new ArrayList<>(page.rows().size());
            for (SharedRow row : page.rows()) {
                Map<String, Object> shared = new LinkedHashMap<>();
                shared.put("key", row.key());
                shared.put("code", row.code());
                shared.put("title", row.title());
                shared.put("document", row.document());
                rows.add(shared);
            }
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("before", page.generationBefore());
            answer.put("after", page.generationAfter());
            answer.put("rows", rows);
            answer.put("next", page.next());
            return new JsonResult<>(answer);
        }));
        ClusterEndpoints.CHANGED.setHandler(conduit -> {
            Row row = LinkedServers.byKey(keyOf(conduit));
            if (row == null) {
                return unknownKey(conduit);
            }
            if (LinkedServers.roleOf(row) != LinkRole.MANAGES) {
                return refusal(conduit, ZenitRefusalReason.FORBIDDEN);
            }
            ClusterRecords.request(row.get(LinkedServerModel.ID));
            return new JsonResult<>(Map.of("asked", true));
        });
        ClusterEndpoints.CODES.setHandler(ClusterHandlers::codes);
        ClusterEndpoints.CALL.setHandler(ClusterHandlers::call);
        ClusterEndpoints.RUN.setHandler(ClusterHandlers::run);
        ClusterEndpoints.JOIN.setHandler(ClusterHandlers::join);
        ClusterEndpoints.STATUS.setHandler(conduit -> {
            Row row = LinkedServers.byKey(keyOf(conduit));
            // A master asking proves it holds the link, so the joining side's row is confirmed; one this side already
            // undid is unknown. A master's own row is confirmed only by an answer to its own call (LinkedServers).
            if (row == null || LinkedServers.roleOf(row) == LinkRole.MANAGED_BY && !LinkedServers.confirmed(row)) {
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

    /** What a call only this server's master may make answers, given that master's row and its caller context. */
    private interface MasterCall {
        @NonNull ActionResult<Object> answer(@NonNull Row master, @NonNull AccessContext caller);
    }

    /**
     * Answers a call of this server's master, read as that master: 401 for a key that names no linked server, 403
     * for one that is not this server's master, the refusal's code for what core refuses.
     */
    private static @NonNull ActionResult<Object> asMaster(@NonNull Conduit conduit, @NonNull MasterCall call) {
        Row row = LinkedServers.byKey(keyOf(conduit));
        if (row == null) {
            return unknownKey(conduit);
        }
        if (LinkedServers.roleOf(row) != LinkRole.MANAGED_BY) {
            return refusal(conduit, ZenitRefusalReason.FORBIDDEN);
        }
        AccessContext caller = masterCaller(row);
        try {
            return ExecutionIdentity.supply(ExecutionIdentity.caller(caller, CallerChannel.ofRequest(conduit)),
                () -> call.answer(row, caller));
        } catch (DomainRefusal refused) {
            return refusal(conduit, refused.reason());
        }
    }

    /** @return the caller a call of this server's master is read as: a LinkedInstallPrincipal of the one reader */
    static @NonNull AccessContext masterCaller(@NonNull Row master) {
        return AccessContext.detached(Reader.READER.principal(master.get(LinkedServerModel.ID),
            String.valueOf((Object) master.get(LinkedServerModel.NAME))), PermissionChecker.DENY_ALL);
    }

    /** @return a refusal answered by its reason's status and code alone */
    private static @NonNull ActionResult<Object> refusal(@NonNull Conduit conduit,
                                                         DomainRefusal.@NonNull Reason reason) {
        conduit.setResponseStatus(reason.status());
        return new JsonResult<>(Map.of("refusal", reason.code()));
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

    /**
     * Grants a server this one manages the code numbers it wants: 401 for a key (or a ticket with it) that names no
     * such server, 403 for this server's own master, 400 for wants that cannot be read or, outside a join, that name a
     * number to lie above (only a joining server's grant is placed above its own numbers).
     */
    private static @NonNull ActionResult<Object> codes(@NonNull Conduit conduit) {
        Map<String, Object> body = bodyOf(conduit);
        String key = keyOf(conduit);
        String peer;
        Principal asker;
        if (body != null && body.get("ticket") instanceof String ticket) {
            peer = ClusterCodes.ticketPeer(ticket, key);
            asker = null;
        } else {
            Row row = LinkedServers.byKey(key);
            if (row != null && LinkedServers.roleOf(row) != LinkRole.MANAGES) {
                return refusal(conduit, ZenitRefusalReason.FORBIDDEN);
            }
            peer = row == null ? null : ClusterRecords.peerKey(row);
            asker = row == null ? null : StoredRecordPrincipal.resolve(LinkedServers.principalOf(row));
            if (asker == null) {
                peer = null;
            }
        }
        if (peer == null) {
            return unknownKey(conduit);
        }
        List<CodeWant> wants = body == null ? null : ClusterCodes.wantsOf(body.get("wants"));
        if (wants == null || asker != null && wants.stream().anyMatch(want -> want.above() > 0)) {
            return refusal(conduit, ZenitRefusalReason.BAD_REQUEST);
        }
        String granted = peer;
        // At joining no linked server exists yet to act as: the ticket is the gate, as the join code is for a join,
        // and the grant is void until the add records that server.
        List<CodeBlock> blocks = asker == null ? ExecutionIdentity.supplyAsSystem(HohenheimSystemPurposes.CLUSTER_JOIN,
            () -> ClusterCodes.grantJoining(granted, wants)) : ExecutionIdentity.supply(ExecutionIdentity.caller(
            AccessContext.detached(asker), CallerChannel.ofRequest(conduit)), () -> ClusterCodes.grant(granted, wants));
        return new JsonResult<>(Map.of("blocks", ClusterCodes.blocksOnWire(blocks)));
    }

    /** Runs a call this server's master carried, as the person it names; see the class note for each answer. */
    private static @NonNull ActionResult<Object> call(@NonNull Conduit conduit) {
        Row row = LinkedServers.byKey(keyOf(conduit));
        if (row == null) {
            return unknownKey(conduit);
        }
        if (LinkedServers.roleOf(row) != LinkRole.MANAGED_BY) {
            return refusal(conduit, ZenitRefusalReason.FORBIDDEN);
        }
        Map<String, Object> body = bodyOf(conduit);
        ClusterCalls.Asked asked = body == null ? null : ClusterCalls.asked(body);
        if (asked == null) {
            return refusal(conduit, ZenitRefusalReason.BAD_REQUEST);
        }
        Map<String, Object> answer;
        try {
            answer = ClusterCalls.runHere(row, Reader.READER.principal(row.get(LinkedServerModel.ID),
                String.valueOf((Object) row.get(LinkedServerModel.NAME))), Reader.READER, asked, conduit);
        } catch (DomainRefusal refused) {
            return refusal(conduit, refused.reason());
        }
        if (answer == null) {
            conduit.setResponseStatus(404);
            return new JsonResult<>(Map.of("error", "unknown call"));
        }
        return new JsonResult<>(Map.of("answer", answer));
    }

    /** Takes a state of a run this server carried to the server telling it; see the class note for each answer. */
    private static @NonNull ActionResult<Object> run(@NonNull Conduit conduit) {
        Row row = LinkedServers.byKey(keyOf(conduit));
        if (row == null) {
            return unknownKey(conduit);
        }
        if (LinkedServers.roleOf(row) != LinkRole.MANAGES) {
            return refusal(conduit, ZenitRefusalReason.FORBIDDEN);
        }
        Map<String, Object> body = bodyOf(conduit);
        UUID invocation = body != null && body.get("invocation") instanceof String id ? Uuids.canonical(id) : null;
        if (invocation == null || !(body.get("state") instanceof Map<?, ?> state)) {
            return refusal(conduit, ZenitRefusalReason.BAD_REQUEST);
        }
        if (!CarriedOperations.relayed(ClusterRecords.peerKey(row), invocation, state)) {
            conduit.setResponseStatus(404);
            return new JsonResult<>(Map.of("error", "no such run"));
        }
        return new JsonResult<>(Map.of("told", true));
    }

    private static @NonNull ActionResult<Object> refused(@NonNull Conduit conduit, @NonNull JoinRefusal refusal) {
        conduit.setResponseStatus(switch (refusal) {
            case TOO_MANY_TRIES -> 429;
            case HAS_MASTER, MANAGES_OTHERS, BUSY -> 409;
            case NO_SUCH_CODE, EXPIRED, UNREADABLE -> 422;
            case CODES -> 502;
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
