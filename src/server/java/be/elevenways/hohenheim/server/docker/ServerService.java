package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The multi-server inventory: persists Docker hosts ({@link ServerModel}) and builds a
 * {@link DockerClient} for each. The implicit {@code local} host is ensured to exist; remote hosts
 * are reached over SSH.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class ServerService {

    public static final String LOCAL = "local";
    public static final String MODE_LOCAL = "local";
    public static final String MODE_SSH = "ssh";

    // Short deadline for the list's reachability probe so a down remote can't hang the page long.
    private static final long PING_TIMEOUT_MS = 8000;

    private final ServerModel model;

    public ServerService() {
        this(HohenheimDatabase.datasource());
    }

    public ServerService(Datasource datasource) {
        this.model = new ServerModel(datasource);
    }

    /** Ensure the implicit local host has a record (idempotent). */
    public void ensureLocal() {
        if (model.findByName(LOCAL) == null) {
            Row row = model.createEmptyRow();
            row.set(ServerModel.NAME, LOCAL);
            row.set(ServerModel.MODE, MODE_LOCAL);
            model.save(row);
        }
    }

    /** A server with its best-effort reachability and host resource snapshot, for the admin list.
     *  All resource fields are 0 when the host is unreachable. */
    public record Summary(String name, String mode, String sshTarget, boolean reachable,
                          int cpus, long memoryBytes, int containersRunning, int containersTotal,
                          int images) {}

    /** All servers with reachability + host stats (from Docker {@code /info}), best-effort per record. */
    public List<Summary> summaries() {
        List<Summary> result = new ArrayList<>();
        for (Row row : model.find().all()) {
            String target = row.get(ServerModel.SSH_TARGET);
            Map<String, Object> info = infoFor(row);
            result.add(new Summary(
                row.get(ServerModel.NAME),
                row.get(ServerModel.MODE),
                target != null ? target : "",
                info != null,
                asInt(info, "NCPU"),
                asLong(info, "MemTotal"),
                asInt(info, "ContainersRunning"),
                asInt(info, "Containers"),
                asInt(info, "Images")));
        }
        return result;
    }

    /** Just the server names (no reachability probe), for form dropdowns. */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Row row : model.find().all()) {
            names.add(row.get(ServerModel.NAME));
        }
        return names;
    }

    /** A DockerClient for the named server (local socket or remote over SSH). */
    public DockerClient clientFor(String name) {
        Row row = model.findByName(name);
        if (row == null) {
            throw new IllegalArgumentException("No server named '" + name + "'");
        }
        return new DockerClient(transportFor(row));
    }

    /** Register (or update) a remote SSH server. */
    public void add(String name, String sshTarget) {
        Row row = model.findByName(name);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(ServerModel.NAME, name);
        }
        row.set(ServerModel.MODE, MODE_SSH);
        row.set(ServerModel.SSH_TARGET, sshTarget);
        model.save(row);
    }

    /** Remove a server; the implicit local host (the machine itself) cannot be removed. */
    public void remove(String name) {
        if (LOCAL.equals(name)) {
            return;
        }
        model.find().where(ServerModel.NAME.eq(name)).delete();
    }

    private static DockerTransport transportFor(Row row) {
        if (MODE_SSH.equals(row.get(ServerModel.MODE))) {
            return ProcessDockerTransport.overSsh(row.get(ServerModel.SSH_TARGET));
        }
        return new UnixSocketDockerTransport(DockerClient.DEFAULT_SOCKET);
    }

    // Host /info (also serves as the reachability probe), or null when unreachable.
    private static Map<String, Object> infoFor(Row row) {
        try {
            return new DockerClient(transportFor(row), PING_TIMEOUT_MS).info();
        } catch (Exception e) {
            return null;
        }
    }

    private static int asInt(Map<String, Object> info, String key) {
        return info != null && info.get(key) instanceof Number n ? n.intValue() : 0;
    }

    private static long asLong(Map<String, Object> info, String key) {
        return info != null && info.get(key) instanceof Number n ? n.longValue() : 0L;
    }
}
