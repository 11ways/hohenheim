package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.cluster.CarriedCallRefusal;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolCarrier;
import be.elevenways.zenit.auth.model.ScopedPrincipal;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.peer.LinkedInstallPrincipal;
import be.elevenways.zenit.common.peer.MasterWord;
import be.elevenways.zenit.common.peer.SharedRowsReader;
import be.elevenways.zenit.common.peer.SpokenForPrincipal;
import be.elevenways.zenit.common.result.ReaderFact;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.peer.PeerRecords;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A tool call about a thing another server owns, both ways: the master carries it ({@link #carry}) to the server it
 * manages that owns the thing, which runs it on the master's word as the person the master names ({@link #runHere}).
 *
 * AIDEV-NOTE: who may speak for a person. Only a master, and only on a server it manages: {@link #runHere} is reached
 * only through ClusterHandlers' tool handler, which answers the linked server whose key it is and whose row says it is
 * THIS server's master (a server this one manages, presenting its own valid key, is refused before anything is read).
 * The master is the one place of accounts and rights: it sends the person's id and name as it knows them and its word
 * (MasterWord): every declared permission the caller holds through its credential there, every record capability that
 * credential covers there, and its scopes. The managed server needs no account for the person and decides nothing of
 * their rights: it runs the call as a SpokenForPrincipal holding exactly that word, through its own MCP server's one
 * choke point
 * ({@link McpHostServer#carriedHere}), which never carries a call on, and its history names the person by the name the
 * master gave, kept as a record of the master (PeerRecords.keep), through the master.
 * AIDEV-NOTE: until grants follow the master, only an administrator of the master is carried; anyone else is refused
 * on the master before anything is sent (NOT_ADMINISTRATOR), so no word widens what a person may see or do.
 * AIDEV-NOTE: a refusal or failure answers the tool in words naming the server, never the master's record of it and
 * never a write here; a call that may change something and got no usable answer says it may or may not have been
 * carried out. After a carried change the master brings its record of that server in step before it answers.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class ClusterToolCalls implements McpToolCarrier {

    private static final ClusterToolCalls CARRIER = new ClusterToolCalls();

    private ClusterToolCalls() {
    }

    /** Makes this server's assistant connection carry calls about its managed servers' things to them. */
    static void install() {
        McpToolCarrier.SEAM.installSole(CARRIER);
    }

    @Override
    public boolean reachesOthers() {
        return LinkedServers.managesAny();
    }

    @Override
    public @NonNull McpResponse carry(@NonNull String install, @NonNull CarriedCall call) {
        AccessContext caller = call.caller();
        UUID id = Uuids.canonical(install);
        Row server = id == null ? null : LinkedServers.byId(id);
        if (server == null || LinkedServers.roleOf(server) != LinkRole.MANAGES) {
            return refused(caller, CarriedCallRefusal.GONE, "");
        }
        String name = String.valueOf((Object) server.get(LinkedServerModel.NAME));
        if (LinkedServers.stopsSending(server)) {
            return refused(caller, CarriedCallRefusal.REFUSED, name);
        }
        if (LinkState.of(server, LinkedServers.ownVersion()) == LinkState.OTHER_VERSION) {
            return refused(caller, CarriedCallRefusal.OTHER_VERSION, name);
        }
        if (!HohenheimAccess.isAdmin(caller) || !(caller.principal() instanceof ScopedPrincipal person)
                || person.reference() == null || !person.reference().kind().account()) {
            return refused(caller, CarriedCallRefusal.NOT_ADMINISTRATOR, name);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("person", String.valueOf(person.reference().id()));
        body.put("name", person.displayName());
        body.putAll(MasterWord.of(caller, person.scopes()).wire());
        body.put("server", call.server());
        body.put("tool", call.tool());
        body.put("arguments", call.arguments());
        LinkCalls.Answer answer = LinkCalls.tool(server.get(LinkedServerModel.BASE_URL),
            server.get(LinkedServerModel.LINK_KEY), body, caller.locales().tags());
        CarriedCallRefusal unsure = call.writes() ? CarriedCallRefusal.MAY_HAVE_ARRIVED
            : CarriedCallRefusal.NOT_ANSWERING;
        return switch (answer) {
            case LinkCalls.Answered answered when answered.status() == 200 -> {
                McpResponse response = answered.body().get("answer") instanceof Map<?, ?> wire ? responseOf(wire)
                    : null;
                if (response == null) {
                    yield refused(caller, unsure, name);
                }
                if (call.writes() && !response.isError()) {
                    ClusterRecords.syncNow(id);
                }
                yield response;
            }
            case LinkCalls.Answered answered when answered.status() == 422 ->
                refused(caller, CarriedCallRefusal.byToken(answered.body().get("refusal")), name);
            case LinkCalls.Answered answered when answered.status() == 401 || answered.status() == 403 ->
                refused(caller, CarriedCallRefusal.REFUSED, name);
            case LinkCalls.Answered answered when answered.status() == 404 || answered.status() == 400 ->
                refused(caller, CarriedCallRefusal.OTHER_VERSION, name);
            case LinkCalls.Answered answered when answered.status() == 429 ->
                refused(caller, CarriedCallRefusal.NOT_ANSWERING, name);
            case LinkCalls.Answered answered -> refused(caller, unsure, name);
            case LinkCalls.Failed failed when failed.failure() == LinkFailure.UNREACHABLE ->
                refused(caller, CarriedCallRefusal.NOT_ANSWERING, name);
            case LinkCalls.Failed failed -> refused(caller, unsure, name);
        };
    }

    /**
     * What a master asked of this server: whom it speaks for, with what word, and the call as its caller sent it.
     *
     * @param person    the person's id on the master
     * @param name      the person's name, as the master names them
     * @param word      what the master says the person may do
     * @param server    the MCP server the call came to
     * @param tool      the tool's name
     * @param arguments the arguments as sent
     */
    record Asked(@NonNull UUID person, @NonNull String name, @NonNull MasterWord word, @NonNull String server,
                 @NonNull String tool, @NonNull Map<String, Object> arguments) {
    }

    /** @return what the body asks, null for a body that cannot be read as a carried call */
    @SuppressWarnings("unchecked")
    static @Nullable Asked asked(@NonNull Map<String, Object> body) {
        MasterWord word = MasterWord.read(body);
        UUID person = body.get("person") instanceof String id ? Uuids.canonical(id) : null;
        if (person == null || !(body.get("name") instanceof String name) || name.isBlank() || word == null
                || !(body.get("server") instanceof String server) || !(body.get("tool") instanceof String tool)
                || !(body.get("arguments") instanceof Map<?, ?> arguments)) {
            return null;
        }
        return new Asked(person, name, word, server, tool, (Map<String, Object>) arguments);
    }

    /** What running a carried call here came to. */
    sealed interface Ran permits Answered, Unknown {
    }

    /** The tool answered, as the person the master speaks for. */
    record Answered(@NonNull McpResponse response) implements Ran {
    }

    /** This build serves no assistant connection of that name. */
    record Unknown() implements Ran {
    }

    /**
     * Runs a call this server's master carried, on the master's word, as the person it names.
     *
     * @param master  this server's master, whose key the call presented
     * @param speaker the principal that master acts as here, minted by {@code reader}
     * @param reader  the one reader of shared rows, which alone speaks for a master's people
     * @param conduit the call's request: it carries the caller's languages, and the person's principal from here on
     * @throws be.elevenways.zenit.common.refusal.DomainRefusal FORBIDDEN when the scopes do not admit the server
     */
    static @NonNull Ran runHere(@NonNull Row master, @NonNull LinkedInstallPrincipal speaker,
                                @NonNull SharedRowsReader reader, @NonNull Asked asked, @NonNull Conduit conduit) {
        McpHostServer server = McpHostServers.routed(asked.server());
        if (server == null) {
            return new Unknown();
        }
        // The person is kept as a record of the master, so history names them by the master's name for them.
        UUID kept = PeerRecords.keep(ClusterRecords.peerKey(master), SpokenForPrincipal.KEPT_AS,
            asked.person().toString(), asked.name(), "{}");
        SpokenForPrincipal person = reader.spokenFor(speaker, kept, asked.name(), asked.word());
        ReaderFact.installForRequest(conduit, person);
        return new Answered(server.carriedHere(AccessContext.of(conduit)).call(asked.tool(), asked.arguments()));
    }

    /** @return a tool's answer as it travels back to the master */
    static @NonNull Map<String, Object> wireOf(@NonNull McpResponse response) {
        List<Map<String, Object>> images = new ArrayList<>();
        for (McpResponse.ImageBlock image : response.images()) {
            images.add(Map.of("data", image.base64Data(), "mime_type", image.mimeType()));
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("text", response.textContent());
        wire.put("error", response.isError());
        wire.put("images", images);
        wire.put("structured", response.structured());
        return wire;
    }

    /** @return the tool's answer a managed server sent back, null for one that cannot be read */
    @SuppressWarnings("unchecked")
    private static @Nullable McpResponse responseOf(@NonNull Map<?, ?> wire) {
        if (!(wire.get("text") instanceof String text) || !(wire.get("error") instanceof Boolean error)
                || !(wire.get("images") instanceof List<?> images)) {
            return null;
        }
        McpResponse response = McpResponse.text(text);
        for (Object item : images) {
            if (!(item instanceof Map<?, ?> image) || !(image.get("data") instanceof String data)
                    || !(image.get("mime_type") instanceof String mimeType)) {
                return null;
            }
            response.image(data, mimeType);
        }
        Object structured = wire.get("structured");
        if (structured instanceof Map<?, ?> facts) {
            response.structured((Map<String, Object>) facts);
        } else if (structured != null) {
            return null;
        }
        return error ? response.asError() : response;
    }

    /** @return the refusal as the tool's answer, in the caller's words */
    private static @NonNull McpResponse refused(@NonNull AccessContext caller, @NonNull CarriedCallRefusal refusal,
                                                @NonNull String name) {
        if (refusal == CarriedCallRefusal.MAY_HAVE_ARRIVED) {
            Blast.log("CLUSTER: a carried tool call to", name, "may or may not have been carried out");
        }
        return McpResponse.error(caller.text(refusal.message(name)));
    }
}
