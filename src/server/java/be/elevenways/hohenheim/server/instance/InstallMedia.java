package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.UrlPolicy;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Operator-published install media (ISO volumes) on one Incus host: the storage half
 * of the interactive-install lane. Media lives in the host's OWN managed pool (the
 * same pool the device lane places volumes in, one authority --
 * {@link IncusInstanceRuntime#managedPoolNameOf}), is per-host exactly like a
 * prepared image, and is referenced by cdrom device rows by NAME.
 *
 * Every entry here is operator work behind the admin permission (the endpoints
 * declare it); this class still refuses non-Incus hosts by name rather than by NPE.
 */
public final class InstallMedia {

    /** Media volume names become daemon volume names; same shape as device names. */
    private static final String NAME_PATTERN = "[a-z0-9][a-z0-9._-]{0,60}";

    /**
     * Hard cap on one fetched ISO. Dual-layer DVD media tops out under 9 GB and every
     * OS install ISO in circulation fits well under this; a cap is what keeps a typoed
     * URL from filling the controller's disk.
     */
    static final long MAX_ISO_BYTES = 16L * 1024 * 1024 * 1024;

    private static final UrlPolicy FETCH_POLICY = UrlPolicy.builder()
        .schemes("http", "https")
        .build();

    /** One listed medium: the volume name plus its daemon-reported description. */
    public record Medium(@NonNull String name, @Nullable String description) {}

    private final @NonNull ServerService servers;

    public InstallMedia() {
        this(new ServerService());
    }

    InstallMedia(@NonNull ServerService servers) {
        this.servers = servers;
    }

    /** The ISO volumes of one host's managed pool, name order. */
    public @NonNull List<Medium> listFor(@NonNull Row server) throws IOException {
        IncusClient incus = clientOf(server);
        String pool = IncusInstanceRuntime.managedPoolNameOf(incus);
        List<Medium> media = new ArrayList<>();
        for (var volume : incus.customVolumes(pool)) {
            if (!"iso".equals(volume.get("content_type"))) {
                continue;
            }
            String name = String.valueOf(volume.get("name"));
            String description = volume.get("description") instanceof String text
                && !text.isBlank() ? text : null;
            media.add(new Medium(name, description));
        }
        media.sort((a, b) -> a.name().compareTo(b.name()));
        return media;
    }

    /**
     * Fetch an ISO from {@code url} and import it into the host's managed pool as an
     * ISO volume named {@code name}. The download lands in a controller temp file
     * first (capped), then STREAMS to the daemon; the temp file is always removed.
     *
     * @throws Violations {@code media_name_invalid}, {@code media_url_invalid},
     *         {@code media_exists}, {@code media_fetch_failed}
     */
    public void fetch(@NonNull Row server, @NonNull String name, @NonNull String url) {
        requireName(name);
        String problem = FETCH_POLICY.problemOf(url);
        if (problem != null) {
            throw Violations.ofField("url", url, violationText("media_url_invalid"));
        }
        try {
            IncusClient incus = clientOf(server);
            String pool = IncusInstanceRuntime.managedPoolNameOf(incus);
            if (incus.customVolume(pool, name) != null) {
                throw Violations.ofField("name", name, violationText("media_exists")
                    .withArg("media", name));
            }
            Path temp = Files.createTempFile("hohenheim-media-", ".iso");
            try {
                download(url, temp);
                incus.importIsoVolume(pool, name, temp);
            } finally {
                Files.deleteIfExists(temp);
            }
            if (incus.customVolume(pool, name) == null) {
                throw new IOException("import was accepted but volume '" + name
                    + "' does not read back on pool '" + pool + "'");
            }
        } catch (IOException e) {
            Blast.log("MEDIA: fetching", name, "onto",
                server.get(ServerModel.NAME), "failed -", e.getMessage());
            throw Violations.ofForm(violationText("media_fetch_failed")
                .withArg("media", name)
                .withArg("reason", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /**
     * Delete one medium, refused while ANY cdrom device row on this host still
     * references it -- a deploy would otherwise fail its reconcile by name later,
     * which is the worse place to find out.
     *
     * @throws Violations {@code media_in_use}, {@code media_delete_failed}
     */
    public void delete(@NonNull Row server, @NonNull String name) {
        requireName(name);
        List<String> holders = referencingInstances(server, name);
        if (!holders.isEmpty()) {
            throw Violations.ofForm(violationText("media_in_use")
                .withArg("media", name)
                .withArg("instances", String.join(", ", holders)));
        }
        try {
            IncusClient incus = clientOf(server);
            String pool = IncusInstanceRuntime.managedPoolNameOf(incus);
            incus.deleteCustomVolume(pool, name);
            if (incus.customVolume(pool, name) != null) {
                throw new IOException("volume '" + name + "' still exists on pool '"
                    + pool + "' after its delete was accepted");
            }
        } catch (IOException e) {
            throw Violations.ofForm(violationText("media_delete_failed")
                .withArg("media", name)
                .withArg("reason", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** Names of instances ON THIS HOST whose cdrom rows reference the medium. */
    static @NonNull List<String> referencingInstances(@NonNull Row server,
                                                      @NonNull String name) {
        Integer serverId = server.get(ServerModel.ID);
        List<String> holders = new ArrayList<>();
        for (Row device : Models.get(InstanceDeviceModel.class).find()
                .where(InstanceDeviceModel.TYPE.eq(InstanceDeviceModel.TYPE_CDROM))
                .where(InstanceDeviceModel.SOURCE_MEDIA.eq(name))
                .all()) {
            Row instance = Models.get(InstanceModel.class)
                .findById(device.get(InstanceDeviceModel.INSTANCE_ID));
            if (instance == null || instance.get(InstanceModel.DELETED_AT) != null) {
                continue;
            }
            int instanceHost = ServerModel.canonicalServerId(
                instance.get(InstanceModel.SERVER_ID));
            if (serverId != null && serverId == instanceHost) {
                holders.add(String.valueOf((Object) instance.get(InstanceModel.NAME)));
            }
        }
        return holders;
    }

    /** Stream one URL into {@code destination}, redirects followed, size capped. */
    private static void download(@NonNull String url, @NonNull Path destination)
            throws IOException {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("download of " + url + " was interrupted");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("download of " + url + " answered HTTP "
                + response.statusCode());
        }
        long total = 0;
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(destination)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_ISO_BYTES) {
                    throw new IOException("download of " + url + " exceeds the "
                        + (MAX_ISO_BYTES >> 30) + " GiB install-media cap");
                }
                out.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new IOException("download of " + url + " carried no body");
        }
    }

    private @NonNull IncusClient clientOf(@NonNull Row server) throws IOException {
        if (!ServerModel.isIncus(server)) {
            throw new IOException("Host '" + server.get(ServerModel.NAME)
                + "' is not an Incus host; install media is an Incus capability");
        }
        try {
            return this.servers.incusClientFor(server.get(ServerModel.NAME));
        } catch (HostKeys.HostTrustException refused) {
            // Client CONSTRUCTION refusals (no pinned certificate yet) are named
            // facts about the host, not page failures -- fold them onto the
            // IOException lane every caller already renders (the tab's load_error,
            // the handlers' flash). Found live: an un-enrolled Incus host 500'd
            // its own media tab. Deliberately NOT catch(IllegalStateException):
            // any other ISE here is a programming error that must surface.
            throw new IOException(refused.getMessage(), refused);
        }
    }

    private static void requireName(@NonNull String name) {
        if (!name.matches(NAME_PATTERN)) {
            throw Violations.ofField("name", name, violationText("media_name_invalid")
                .withArg("name", name));
        }
    }

    private static Microcopy violationText(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
