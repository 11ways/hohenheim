package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.result.RenderTemplateResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Server request handlers.
 */
public final class ServerHandlers {

    // AIDEV-NOTE: SSH target must be a bare [user@]host[:port] that never starts with '-', so it
    // cannot be parsed as an ssh option (e.g. -oProxyCommand=...) when passed to the ssh argv. The
    // host may be a DNS/IPv4 name or a bracketed IPv6 literal ([2001:db8::1]).
    private static final Pattern SSH_TARGET =
        Pattern.compile("^(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*@)?(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*|\\[[0-9A-Fa-f:]+\\])(?::[0-9]{1,5})?$");

    private ServerHandlers() {
    }

    public static void init() {
        ServerService serverService = new ServerService();
        serverService.ensureLocal();

        // List
        HohenheimEndpoints.SERVERS.setHandler(conduit -> {
            serverService.ensureLocal();
            List<Map<String, Object>> items = new ArrayList<>();
            for (ServerService.Summary summary : serverService.summaries()) {
                boolean up = summary.reachable();
                Map<String, Object> item = new HashMap<>();
                item.put("name", summary.name());
                item.put("mode", summary.mode());
                item.put("sshTarget", summary.sshTarget());
                item.put("reachable", up);
                item.put("removable", !ServerService.LOCAL.equals(summary.name()));
                item.put("cpus", up ? String.valueOf(summary.cpus()) : "-");
                item.put("memory", up
                    ? String.format("%.1f GB", summary.memoryBytes() / 1_000_000_000.0) : "-");
                item.put("containers", up
                    ? summary.containersRunning() + "/" + summary.containersTotal() : "-");
                item.put("images", up ? String.valueOf(summary.images()) : "-");
                items.add(item);
            }
            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/servers/list"),
                Map.of("servers", items)
            );
        });

        // Create form
        HohenheimEndpoints.SERVERS_CREATE_FORM.setHandler(conduit ->
            new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/servers/create"),
                Map.of("error", "")
            )
        );

        // Create (POST)
        HohenheimEndpoints.SERVERS_CREATE.setHandler(conduit -> {
            Map<String, String> form = HandlerSupport.formMap(conduit);
            String name = form.getOrDefault("name", "").trim();
            String sshTarget = form.getOrDefault("ssh_target", "").trim();

            String error = validateServerForm(name, sshTarget);
            if (error != null) {
                return HandlerSupport.renderUntyped(Identifier.of("hohenheim", "hohenheim/servers/create"),
                    Map.of("error", error));
            }
            serverService.add(name, sshTarget);
            HandlerSupport.audit(conduit, AuditLogModel.ACTION_CREATED, AuditLogModel.RESOURCE_SERVER, name, name);
            return HandlerSupport.redirectUntyped("/servers");
        });

        // Delete (POST)
        HohenheimEndpoints.SERVERS_DELETE.setHandler(conduit -> {
            String name = conduit.getParameter(HohenheimEndpoints.SERVER_NAME);
            serverService.remove(name);
            HandlerSupport.audit(conduit, AuditLogModel.ACTION_DELETED, AuditLogModel.RESOURCE_SERVER, name, name);
            return HandlerSupport.redirectUntyped("/servers");
        });
    }

    private static String validateServerForm(String name, String sshTarget) {
        String nameError = HandlerSupport.validateName(name);
        if (nameError != null) return nameError;
        if (ServerService.LOCAL.equals(name)) return "'local' is reserved for this host";
        if (sshTarget.isEmpty()) return "SSH target is required (e.g. user@host)";
        if (!SSH_TARGET.matcher(sshTarget).matches()) return "SSH target must be a plain [user@]host[:port]";
        return null;
    }
}
