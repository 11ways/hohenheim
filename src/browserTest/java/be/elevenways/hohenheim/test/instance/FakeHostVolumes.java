package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.VolumeOperations;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A directory-backed host volume storage per host record, installed on {@link VolumeOperations#HOSTS}: every host
 * keeps its volumes under its own directory, so a move between two host records crosses storage the way two
 * machines do.
 *
 * AIDEV-NOTE: the payload format is a zip, not a tar; only this fake writes and reads it, which is all the backup
 * and migration lanes ask of a payload (opaque, checksummed bytes).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class FakeHostVolumes implements VolumeOperations.Hosts {

    /** What every fake host reports free on its volume storage. */
    static volatile long availableBytes = Long.MAX_VALUE / 4;

    /** The uid each restored volume (host path on a host) was last given, by {@code <server>:<path>}. */
    static final Map<String, Integer> RESTORED_OWNERS = new ConcurrentHashMap<>();

    private final Path root;

    private FakeHostVolumes(@NonNull Path root) {
        this.root = root;
    }

    /** Install a fresh storage tree; {@link #uninstall} puts the real hosts back. */
    static @NonNull FakeHostVolumes install() throws IOException {
        FakeHostVolumes fake = new FakeHostVolumes(Files.createTempDirectory("fake-host-volumes"));
        VolumeOperations.HOSTS.install(fake);
        availableBytes = Long.MAX_VALUE / 4;
        RESTORED_OWNERS.clear();
        return fake;
    }

    static void uninstall() {
        VolumeOperations.HOSTS.uninstall();
    }

    /** Where one host keeps one volume path. */
    @NonNull Path directoryOf(@NonNull String serverName, @NonNull String hostPath) {
        return this.root.resolve(serverName).resolve(hostPath.replaceFirst("^/+", ""));
    }

    @Override
    public @NonNull VolumeOperations forServer(@NonNull Row server) {
        return new Host(String.valueOf((Object) server.get(ServerModel.NAME)));
    }

    @Override
    public @NonNull String storageOf(@NonNull Row server) {
        return String.valueOf((Object) server.get(ServerModel.NAME));
    }

    private final class Host implements VolumeOperations {

        private final String server;

        Host(@NonNull String server) {
            this.server = server;
        }

        private Path dir(String hostPath) {
            return directoryOf(this.server, hostPath);
        }

        @Override
        public void create(@NonNull String hostPath) {
            try {
                Files.createDirectories(dir(hostPath));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void setQuota(@NonNull String hostPath, @Nullable Long bytes) {
        }

        @Override
        public long usage(@NonNull String hostPath) {
            return -1;
        }

        @Override
        public @NonNull String snapshot(@NonNull String hostPath, @NonNull String label) {
            return hostPath + "@" + label;
        }

        @Override
        public void deleteSnapshot(@NonNull String snapshotPath) {
        }

        @Override
        public void destroy(@NonNull String hostPath) {
            Path directory = dir(hostPath);
            if (!Files.exists(directory)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(directory)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void own(@NonNull String hostPath, int uid) {
        }

        @Override
        public long archive(@NonNull String hostPath, @NonNull Path target, long maxBytes) {
            Path directory = dir(hostPath);
            try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out);
                 Stream<Path> walk = Files.walk(directory)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    zip.putNextEntry(new ZipEntry(directory.relativize(file).toString()));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            try {
                return Files.size(target);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void restore(@NonNull String hostPath, @NonNull Path source, @Nullable Integer ownerUid) {
            Path directory = dir(hostPath);
            try (InputStream in = Files.newInputStream(source); ZipInputStream zip = new ZipInputStream(in)) {
                for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    Path file = directory.resolve(entry.getName()).normalize();
                    Files.createDirectories(file.getParent());
                    Files.copy(zip, file);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (ownerUid != null) {
                RESTORED_OWNERS.put(this.server + ":" + hostPath, ownerUid);
            }
        }

        @Override
        public long availableBytes(@NonNull String path) {
            return availableBytes;
        }
    }
}
