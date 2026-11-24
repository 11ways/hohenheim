package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.cluster.CarriedCallRefusal;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.auth.ProteusSignIn;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolCarrier;
import be.elevenways.zenit.auth.model.HostCredentialPrincipal;
import be.elevenways.zenit.auth.model.ScopedAccountPrincipal;
import be.elevenways.zenit.auth.server.HostCredentialPrincipals;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ReaderFact;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A tool call about a thing another server owns, both ways: the master carries it ({@link #carry}) to the server it
 * manages that owns the thing, which runs it as the person's own account there ({@link #runHere}).
 *
 * AIDEV-NOTE: who may speak for a person. Only a master, and only on a server it manages: {@link #runHere} is reached
 * only through ClusterHandlers' tool handler, which answers the linked server whose key it is and whose row says it is
 * THIS server's master (a server this one manages, presenting its own valid key, is refused before anything is read).
 * The master names the person by the Proteus sign-in both servers share (SharedSignIn: realm and subjects, never an
 * address); this server finds its own enabled account linked to those subjects and runs the call as that account
 * (HostCredentialPrincipal, the master's name as the credential every write is credited through), narrowed to the
 * scopes the caller's credential carries on the master, through its own MCP server's one choke point
 * ({@link McpHostServer#carriedHere}), which never carries a call on. No administrator of the master is anything
 * here without an own account, and a caller that is no person signed in with Proteus is refused on the master
 * before anything is sent.
 *
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
        String realm = ProteusSignIn.realm();
        if (realm == null || !(caller.principal() instanceof ScopedAccountPrincipal person)) {
            return refused(caller, CarriedCallRefusal.NO_SHARED_SIGN_IN, name);
        }
        List<String> subjects = SharedSignIn.subjectsOf(person.reference().id());
        if (subjects.isEmpty()) {
            return refused(caller, CarriedCallRefusal.NO_SHARED_SIGN_IN, name);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("realm", realm);
        body.put("subjects", subjects);
        body.put("server", call.server());
        body.put("tool", call.tool());
        body.put("arguments", call.arguments());
        body.put("scopes", person.scopes());
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
     * What a master asked of this server: who asks, by the sign-in both share, and the call as its caller sent it.
     *
     * @param realm     the master's Proteus realm
     * @param subjects  the person's subjects in that realm
     * @param server    the MCP server the call came to
     * @param tool      the tool's name
     * @param arguments the arguments as sent
     * @param scopes    the scopes the caller's credential carries on the master
     */
    record Asked(@NonNull String realm, @NonNull List<String> subjects, @NonNull String server, @NonNull String tool,
                 @NonNull Map<String, Object> arguments, @NonNull List<String> scopes) {
    }

    /** @return what the body asks, null for a body that cannot be read as a carried call */
    @SuppressWarnings("unchecked")
    static @Nullable Asked asked(@NonNull Map<String, Object> body) {
        List<String> subjects = texts(body.get("subjects"));
        List<String> scopes = texts(body.get("scopes"));
        if (!(body.get("realm") instanceof String realm) || subjects == null || subjects.isEmpty() || scopes == null
                || !(body.get("server") instanceof String server) || !(body.get("tool") instanceof String tool)
                || !(body.get("arguments") instanceof Map<?, ?> arguments)) {
            return null;
        }
        return new Asked(realm, subjects, server, tool, (Map<String, Object>) arguments, scopes);
    }

    /** What running a carried call here came to. */
    sealed interface Ran permits Answered, Refused, Unknown {
    }

    /** The tool answered, as the person's account here. */
    record Answered(@NonNull McpResponse response) implements Ran {
    }

    /** Nobody here may be named by what the master sent; nothing was read or written. */
    record Refused(@NonNull CarriedCallRefusal refusal) implements Ran {
    }

    /** This build serves no assistant connection of that name. */
    record Unknown() implements Ran {
    }

    /**
     * Runs a call this server's master carried, as the person's own account here.
     *
     * @param master  this server's master, whose key the call presented
     * @param conduit the call's request: it carries the caller's languages, and the person's principal from here on
     * @throws be.elevenways.zenit.common.refusal.DomainRefusal FORBIDDEN when the scopes do not admit the server
     */
    static @NonNull Ran runHere(@NonNull Row master, @NonNull Asked asked, @NonNull Conduit conduit) {
        String realm = ProteusSignIn.realm();
        if (realm == null || !realm.equals(asked.realm())) {
            return new Refused(CarriedCallRefusal.OTHER_REALM);
        }
        McpHostServer server = McpHostServers.routed(asked.server());
        if (server == null) {
            return new Unknown();
        }
        UUID account = SharedSignIn.accountOf(asked.subjects());
        // The master's name as this server calls it is the credential: what every write is credited through.
        HostCredentialPrincipal person = account == null ? null : HostCredentialPrincipals.forAccount(account,
            String.valueOf((Object) master.get(LinkedServerModel.NAME)), asked.scopes());
        if (person == null) {
            return new Refused(CarriedCallRefusal.NO_ACCOUNT);
        }
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

    /** @return the texts a list holds, null for anything that is no list of texts */
    private static @Nullable List<String> texts(@Nullable Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> texts = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text)) {
                return null;
            }
            texts.add(text);
        }
        return texts;
    }
}
