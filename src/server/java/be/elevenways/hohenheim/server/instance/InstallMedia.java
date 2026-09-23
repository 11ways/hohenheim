package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.DeviceType;
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
import be.elevenways.zenit.server.net.BodySink;
import be.elevenways.zenit.server.net.FetchFailure;
import be.elevenways.zenit.server.net.FetchOutcome;
import be.elevenways.zenit.server.net.FetchRequest;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import be.elevenways.zenit.server.net.PinnedFetcher;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    public static final long MAX_ISO_BYTES = 16L * 1024 * 1024 * 1024;

    private static final UrlPolicy FETCH_POLICY = UrlPolicy.builder()
        .schemes("http", "https")
        .build();

    /** The whole-exchange bound of one fetch: 16 GiB at roughly 2.3 MB/s, then refused. */
    static final Duration FETCH_DEADLINE = Duration.ofHours(2);

    /**
     * THE fetch of a media URL: public addresses only, every redirect hop re-checked and
     * pinned, one deadline over the whole exchange and the ISO cap on the decoded body.
     *
     * AIDEV-NOTE: public-internet only although only an operator reaches this lane. The
     * fetch runs ON the controller, so a private URL (the cloud metadata address, a
     * loopback admin port, another host's LAN service) is the controller's network
     * reached on someone's say-so; a stolen operator session must not turn this form into
     * that. An ISO on a private mirror has a lane already: the operator uploads it
     * (SERVERS_MEDIA_UPLOAD), which moves bytes the operator holds and reaches nothing.
     */
    private static final PinnedFetcher FETCHER = PinnedFetcher.builder(OutboundUrlGuard.PUBLIC_INTERNET)
        .connectTimeout(Duration.ofSeconds(30))
        .deadline(FETCH_DEADLINE)
        .maxBodyBytes(MAX_ISO_BYTES)
        .redirects(PinnedFetcher.Redirects.follow(5))
        .userAgent("Hohenheim")
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
     * AIDEV-NOTE: synchronous on the request thread, bounded by {@link #FETCH_DEADLINE}.
     * A background job would free the thread, but the Install media tab (cms) has no
     * status lane to show a running fetch or its failure on, and a failure only a log
     * line knows about is invisible; moving it off-thread is a page change first.
     *
     * @throws Violations {@code media_name_invalid}, {@code media_url_invalid},
     *         {@code media_url_not_public}, {@code media_exists}, {@code media_fetch_failed}
     */
    public void fetch(@NonNull Row server, @NonNull String name, @NonNull String url) {
        requireName(name);
        String problem = FETCH_POLICY.problemOf(url);
        if (problem != null) {
            throw Violations.ofField("url", url, violationText("media_url_invalid"));
        }
        // The named refusal, before any daemon contact: the fetcher asks the SAME guard
        // again per hop, so this is a message, never the only gate.
        if (OutboundUrlGuard.PUBLIC_INTERNET.check(url) instanceof OutboundUrlGuard.Refused) {
            throw Violations.ofField("url", url, violationText("media_url_not_public"));
        }
        try {
            IncusClient incus = clientOf(server);
            String pool = IncusInstanceRuntime.managedPoolNameOf(incus);
            requireAbsent(incus, pool, name);
            Path temp = Files.createTempFile("hohenheim-media-", ".iso");
            try {
                download(url, temp);
                incus.importIsoVolume(pool, name, temp);
            } finally {
                Files.deleteIfExists(temp);
            }
            requirePresent(incus, pool, name);
        } catch (IOException e) {
            Blast.log("MEDIA: fetching", name, "onto",
                server.get(ServerModel.NAME), "failed -", e.getMessage());
            throw Violations.ofForm(violationText("media_fetch_failed")
                .withArg("media", name)
                .withArg("reason", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /**
     * Import an ISO ALREADY on the controller's disk (an operator's upload) into the
     * host's managed pool as an ISO volume named {@code name}.
     *
     * The caller owns {@code source} and its removal: this is the half of {@link #fetch}
     * below the download, split out so an upload never has to invent a URL for a file
     * the operator already has.
     *
     * @throws Violations {@code media_name_invalid}, {@code media_exists},
     *         {@code media_fetch_failed}
     */
    public void importFrom(@NonNull Row server, @NonNull String name, @NonNull Path source) {
        requireName(name);
        try {
            IncusClient incus = clientOf(server);
            String pool = IncusInstanceRuntime.managedPoolNameOf(incus);
            requireAbsent(incus, pool, name);
            incus.importIsoVolume(pool, name, source);
            requirePresent(incus, pool, name);
        } catch (IOException e) {
            Blast.log("MEDIA: importing", name, "onto",
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
                .where(InstanceDeviceModel.TYPE.eq(DeviceType.CDROM.token()))
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

    /** Stream one URL into {@code destination} through {@link #FETCHER}; a partial file is the caller's to delete. */
    private static void download(@NonNull String url, @NonNull Path destination)
            throws IOException {
        long[] total = {0};
        FetchOutcome outcome;
        try (OutputStream out = Files.newOutputStream(destination)) {
            outcome = FETCHER.fetch(FetchRequest.get(url), new SuccessSink(out, total));
        }
        switch (outcome) {
            case FetchOutcome.Fetched fetched -> {
                if (fetched.status() < 200 || fetched.status() >= 300) {
                    throw new IOException("download answered HTTP " + fetched.status());
                }
                if (total[0] == 0) {
                    throw new IOException("download carried no body");
                }
            }
            case FetchOutcome.Redirected redirected ->
                throw new IOException("download redirected to a target that may not be followed ("
                    + OutboundUrlGuard.originOf(redirected.location()) + ")");
            case FetchOutcome.Refused refused -> throw new IOException(refused.reason());
            case FetchOutcome.Failed failed -> throw new IOException(
                failed.kind() == FetchFailure.TOO_LARGE
                    ? "download exceeds the " + (MAX_ISO_BYTES >> 30) + " GiB install-media cap"
                    : failed.reason());
        }
    }

    /** Writes a 2xx body to the temp file, counting it; any other status is skipped unread. */
    private record SuccessSink(@NonNull OutputStream out, long @NonNull [] total)
            implements BodySink {

        @Override
        public boolean accepts(int status, @NonNull Map<String, List<String>> headers) {
            return status >= 200 && status < 300;
        }

        @Override
        public void write(byte @NonNull [] bytes, int offset, int length) throws IOException {
            this.total[0] += length;
            this.out.write(bytes, offset, length);
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

    /** Refuse a name the pool already holds, before anything is transferred. */
    private static void requireAbsent(@NonNull IncusClient incus, @NonNull String pool,
                                      @NonNull String name) throws IOException {
        if (incus.customVolume(pool, name) != null) {
            throw Violations.ofField("name", name, violationText("media_exists")
                .withArg("media", name));
        }
    }

    /** An accepted import that does not read back is a failure, not a success. */
    private static void requirePresent(@NonNull IncusClient incus, @NonNull String pool,
                                       @NonNull String name) throws IOException {
        if (incus.customVolume(pool, name) == null) {
            throw new IOException("import was accepted but volume '" + name
                + "' does not read back on pool '" + pool + "'");
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
