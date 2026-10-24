package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A docker whose exec "succeeds", answers {@code stat} with the scripted dump's size, records every command with its
 * env, and serves the scripted bytes from any path: a managed-database dump's capture lanes without a daemon.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class ScriptedDumpDocker extends DockerClient {

    final List<List<String>> commands = new ArrayList<>();
    final List<List<String>> envs = new ArrayList<>();
    final List<String> fetched = new ArrayList<>();
    private final byte[] dumpBytes;

    ScriptedDumpDocker(byte[] dumpBytes) {
        super((DockerTransport) null);
        this.dumpBytes = dumpBytes;
    }

    /** The terminal overload: every shorter one, whatever deadline it passes, lands here. */
    @Override
    public ExecResult exec(String containerId, List<String> command, List<String> env,
                           @Nullable String user, @Nullable String workdir, long timeoutMs) throws IOException {
        this.commands.add(List.copyOf(command));
        this.envs.add(List.copyOf(env));
        if (!command.isEmpty() && command.get(0).equals("stat")) {
            return new ExecResult(0, this.dumpBytes.length + "\n", "");
        }
        return new ExecResult(0, "", "");
    }

    @Override
    public long getArchiveFileTo(String containerId, String path,
                                 Path outFile, long maxBytes) throws IOException {
        this.fetched.add(path);
        Files.write(outFile, this.dumpBytes);
        return this.dumpBytes.length;
    }

    /** The directory argument of the {@code mkdir -m 700 <dir>} this capture ran. */
    String privateDirectory() {
        for (List<String> command : this.commands) {
            if (command.size() == 4 && command.get(0).equals("mkdir")) {
                return command.get(3);
            }
        }
        return null;
    }

    /** Whether an {@code rm -rf} removed the directory (and the legacy fixed dump paths). */
    boolean removed(String directory) {
        for (List<String> command : this.commands) {
            if (command.size() > 2 && command.get(0).equals("rm") && command.contains(directory)
                    && command.contains("/tmp/hohenheim-dump.rdb")
                    && command.contains("/tmp/hohenheim-dump.archive")) {
                return true;
            }
        }
        return false;
    }
}
