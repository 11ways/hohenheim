package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.cluster.CarriedCallRefusal;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.ai.server.mcp.host.McpCarriedCalls;
import be.elevenways.zenit.auth.model.ScopedPrincipal;
import be.elevenways.zenit.common.bootstrap.RuntimeJobs;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.peer.LinkedInstallPrincipal;
import be.elevenways.zenit.common.peer.MasterWord;
import be.elevenways.zenit.common.peer.SharedRowsReader;
import be.elevenways.zenit.common.peer.SpokenForPrincipal;
import be.elevenways.zenit.common.result.ReaderFact;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.server.operation.CarriedOperations;
import be.elevenways.zenit.server.peer.PeerCalls;
import be.elevenways.zenit.server.peer.PeerRecords;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hohenheim's link as core's one carrier of calls about things another server owns ({@link PeerCalls}), both ways:
 * the master carries a call (an assistant's tool call, a panel's operation) to the server it manages that owns the
 * thing ({@link #carry}), which runs it on the master's word as the person the master names ({@link #runHere}); a run
 * that call started there tells the master each state it records ({@link #relay}).
 *
 * AIDEV-NOTE: who may speak for a person. Only a master, and only on a server it manages: {@link #runHere} is reached
 * only through ClusterHandlers' call handler, which answers the linked server whose key it is and whose row says it is
 * THIS server's master (a server this one manages, presenting its own valid key, is refused before anything is read).
 * The master is the one place of accounts and rights: it sends the person's id and name as it knows them and its word
 * (MasterWord): every declared permission the caller holds through its credential there, every record capability that
 * credential covers there, and its scopes. The managed server needs no account for the person and decides nothing of
 * their rights: it runs the call as a SpokenForPrincipal holding exactly that word, through the surface's own choke
 * point here (core's operation pipeline, the MCP server's carriedHere), which never carries a call on, and its history
 * names the person by the name the master gave, kept as a record of the master (PeerRecords.keep), through the master.
 * AIDEV-NOTE: until grants follow the master, only an administrator of the master is carried; anyone else is refused
 * on the master before anything is sent (NOT_ADMINISTRATOR), so no word widens what a person may see or do.
 * AIDEV-NOTE: a refusal or failure answers in words naming the server, never the master's record of it and never a
 * write here; a call that may change something and got no usable answer says it may or may not have been carried
 * out. After a carried change the master brings its record of that server in step before it answers.
 * AIDEV-NOTE: a relayed run state is pushed, never polled: each recorded state replaces the one still waiting for its
 * invocation, so a burst sends the latest, and an ending state waiting is never replaced by a progress write that
 * raced it. A state the master did not take is not sent again: the master's run then ends after its silence, in words
 * saying it may still run here.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class ClusterToolCalls implements PeerCalls.Carrier {

    private static final ClusterToolCalls CARRIER = new ClusterToolCalls();

    private static final JobRunnerGroup.Member RELAYS =
        RuntimeJobs.GROUP.member("hh-cluster-relays", JobRunnerGroup.StopMode.DRAIN, 1);

    private static final Coalesced RELAY = new Coalesced(RELAYS, ClusterToolCalls::sendRelays);

    /** The latest state of each relayed run not sent yet, by the invocation the master carried it under. */
    private static final Map<UUID, Map<String, Object>> PENDING = new ConcurrentHashMap<>();

    private ClusterToolCalls() {
    }

    /** Makes this server carry calls about its managed servers' things, and serve the calls its master carries. */
    static void install() {
        PeerCalls.SEAM.installSole(CARRIER);
        CarriedOperations.serve();
        McpCarriedCalls.serve();
    }

    /**
     * Waits for the run states this process relays to be sent, for tests.
     *
     * @return false when one was still on its way after the timeout
     */
    static boolean awaitRelayed(@NonNull Duration timeout) {
        return RELAYS.awaitIdle(timeout);
    }

    @Override
    public boolean reachesOthers() {
        return LinkedServers.managesAny();
    }

    @Override
    public PeerCalls.@NonNull Answer carry(@NonNull String install, PeerCalls.@NonNull Call call) {
        AccessContext caller = call.caller();
        UUID id = Uuids.canonical(install);
        Row server = id == null ? null : LinkedServers.byId(id);
        if (server == null || LinkedServers.roleOf(server) != LinkRole.MANAGES) {
            return refused(CarriedCallRefusal.GONE, "");
        }
        String name = String.valueOf((Object) server.get(LinkedServerModel.NAME));
        if (LinkedServers.stopsSending(server)) {
            return refused(CarriedCallRefusal.REFUSED, name);
        }
        if (LinkState.of(server, LinkedServers.ownVersion()) == LinkState.OTHER_VERSION) {
            return refused(CarriedCallRefusal.OTHER_VERSION, name);
        }
        // The person, signed in here or through a key of theirs: an account, its scopes the key's (a session
        // declares none), never a principal that is no account.
        Principal person = caller.principal();
        PrincipalRef reference = person.reference();
        if (!HohenheimAccess.isAdmin(caller) || reference == null || !reference.kind().account()) {
            return refused(CarriedCallRefusal.NOT_ADMINISTRATOR, name);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("person", String.valueOf(reference.id()));
        body.put("name", person.displayName());
        body.putAll(MasterWord.of(caller, person instanceof ScopedPrincipal scoped ? scoped.scopes() : List.of())
            .wire());
        body.put("surface", call.surface().toString());
        body.put("call", call.body());
        LinkCalls.Answer answer = LinkCalls.carried(server.get(LinkedServerModel.BASE_URL),
            server.get(LinkedServerModel.LINK_KEY), body, caller.locales().tags());
        CarriedCallRefusal unsure = call.writes() ? CarriedCallRefusal.MAY_HAVE_ARRIVED
            : CarriedCallRefusal.NOT_ANSWERING;
        return switch (answer) {
            case LinkCalls.Answered answered when answered.status() == 200 -> {
                if (!(answered.body().get("answer") instanceof Map<?, ?> wire)) {
                    yield refused(unsure, name);
                }
                if (call.writes()) {
                    ClusterRecords.syncNow(id);
                }
                Map<String, Object> carried = new LinkedHashMap<>();
                wire.forEach((key, value) -> carried.put(String.valueOf(key), value));
                yield new PeerCalls.Answered(carried);
            }
            case LinkCalls.Answered answered when answered.status() == 422 ->
                refused(CarriedCallRefusal.byToken(answered.body().get("refusal")), name);
            case LinkCalls.Answered answered when answered.status() == 401 || answered.status() == 403 ->
                refused(CarriedCallRefusal.REFUSED, name);
            case LinkCalls.Answered answered when answered.status() == 404 || answered.status() == 400 ->
                refused(CarriedCallRefusal.OTHER_VERSION, name);
            case LinkCalls.Answered answered when answered.status() == 429 ->
                refused(CarriedCallRefusal.NOT_ANSWERING, name);
            case LinkCalls.Answered answered -> refused(unsure, name);
            case LinkCalls.Failed failed when failed.failure() == LinkFailure.UNREACHABLE ->
                refused(CarriedCallRefusal.NOT_ANSWERING, name);
            case LinkCalls.Failed failed -> refused(unsure, name);
        };
    }

    @Override
    public boolean relay(@NonNull UUID invocation, @NonNull Map<String, Object> state) {
        if (LinkedServers.master() == null) {
            return false;
        }
        // The latest state replaces the one still waiting, except an ending one: a progress write that raced the
        // run's end never hides how it ended.
        Map<String, Object> told = Map.copyOf(withoutNulls(state));
        PENDING.merge(invocation, told, (waiting, latest) -> ended(waiting) && !ended(latest) ? waiting : latest);
        RELAY.ask();
        return true;
    }

    /** @return whether the state is one a run ends with */
    private static boolean ended(@NonNull Map<String, Object> state) {
        OperationRunStatus status = state.get("status") instanceof String key ? OperationRunStatus.fromKey(key) : null;
        return status != null && status.isTerminal();
    }

    /** Sends every run state waiting to this server's master, each once, the latest of its run. */
    private static void sendRelays() {
        Row master = LinkedServers.master();
        for (UUID invocation : PENDING.keySet()) {
            Map<String, Object> state = PENDING.remove(invocation);
            if (state == null || master == null || LinkedServers.stopsSending(master)) {
                continue;
            }
            LinkCalls.Answer answer = LinkCalls.run(master.get(LinkedServerModel.BASE_URL),
                master.get(LinkedServerModel.LINK_KEY), Map.of("invocation", invocation.toString(), "state", state));
            if (!(answer instanceof LinkCalls.Answered answered) || answered.status() != 200) {
                Blast.log("CLUSTER: the master was not told how a run it carried here goes");
            }
        }
    }

    private static @NonNull Map<String, Object> withoutNulls(@NonNull Map<String, Object> state) {
        Map<String, Object> kept = new LinkedHashMap<>();
        state.forEach((key, value) -> {
            if (value != null) {
                kept.put(key, value);
            }
        });
        return kept;
    }

    /**
     * What a master asked of this server: whom it speaks for, with what word, and the call as its placement sent it.
     *
     * @param person  the person's id on the master
     * @param name    the person's name, as the master names them
     * @param word    what the master says the person may do
     * @param surface the placement the call comes from
     * @param call    the call as that surface's data
     */
    record Asked(@NonNull UUID person, @NonNull String name, @NonNull MasterWord word, @NonNull Identifier surface,
                 @NonNull Map<String, Object> call) {
    }

    /** @return what the body asks, null for a body that cannot be read as a carried call */
    @SuppressWarnings("unchecked")
    static @Nullable Asked asked(@NonNull Map<String, Object> body) {
        MasterWord word = MasterWord.read(body);
        UUID person = body.get("person") instanceof String id ? Uuids.canonical(id) : null;
        Identifier surface = body.get("surface") instanceof String id ? Identifier.tryParse(id) : null;
        if (person == null || !(body.get("name") instanceof String name) || name.isBlank() || word == null
                || surface == null || !(body.get("call") instanceof Map<?, ?> call)) {
            return null;
        }
        return new Asked(person, name, word, surface, (Map<String, Object>) call);
    }

    /**
     * Runs a call this server's master carried, on the master's word, as the person it names.
     *
     * @param master  this server's master, whose key the call presented
     * @param speaker the principal that master acts as here, minted by {@code reader}
     * @param reader  the one reader of shared rows, which alone speaks for a master's people
     * @param conduit the call's request: it carries the caller's languages, and the person's principal from here on
     * @return the surface's answer, null for a surface or a call this build does not serve
     * @throws be.elevenways.zenit.common.refusal.DomainRefusal FORBIDDEN when the scopes do not admit the surface
     */
    static @Nullable Map<String, Object> runHere(@NonNull Row master, @NonNull LinkedInstallPrincipal speaker,
                                                 @NonNull SharedRowsReader reader, @NonNull Asked asked,
                                                 @NonNull Conduit conduit) {
        if (!PeerCalls.serves(asked.surface())) {
            return null;
        }
        // The person is kept as a record of the master, so history names them by the master's name for them.
        UUID kept = PeerRecords.keep(ClusterRecords.peerKey(master), SpokenForPrincipal.KEPT_AS,
            asked.person().toString(), asked.name(), "{}");
        SpokenForPrincipal person = reader.spokenFor(speaker, kept, asked.name(), asked.word());
        ReaderFact.installForRequest(conduit, person);
        return PeerCalls.runHere(asked.surface(), asked.call(), AccessContext.of(conduit));
    }

    /** @return the refusal as the carrier's answer */
    private static PeerCalls.@NonNull Answer refused(@NonNull CarriedCallRefusal refusal, @NonNull String name) {
        if (refusal == CarriedCallRefusal.MAY_HAVE_ARRIVED) {
            Blast.log("CLUSTER: a carried call to", name, "may or may not have been carried out");
        }
        return new PeerCalls.Refused(refusal.message(name));
    }
}
