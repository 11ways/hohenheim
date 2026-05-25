package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.util.ArrayList;
import java.util.List;

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

    /** A server plus its best-effort reachability (a Docker ping), for the admin list. */
    public record Summary(String name, String mode, String sshTarget, boolean reachable) {}

    /** All servers with reachability, best-effort per record. */
    public List<Summary> summaries() {
        List<Summary> result = new ArrayList<>();
        for (Row row : model.find().all()) {
            String target = row.get(ServerModel.SSH_TARGET);
            result.add(new Summary(
                row.get(ServerModel.NAME),
                row.get(ServerModel.MODE),
                target != null ? target : "",
                isReachable(row)));
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

    private static boolean isReachable(Row row) {
        try {
            return new DockerClient(transportFor(row), PING_TIMEOUT_MS).ping();
        } catch (Exception e) {
            return false;
        }
    }
}
