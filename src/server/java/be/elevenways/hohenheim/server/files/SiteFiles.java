package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.protoblast.common.util.BlastThread;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.io.FilesystemNodeKind;
import be.elevenways.zenit.server.storage.StorageAdapter;
import be.elevenways.zenit.server.storage.StoredObject;
import be.elevenways.zenit.server.storage.Storages;
import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where a static-files site that serves its own files keeps them: a folder of keys ({@code sites/<id>/}) in the
 * storage the site names, the installation's own site-files storage unless an administrator named another.
 *
 * AIDEV-NOTE: the storage is path-addressed through core's {@link StorageAdapter}: the file a person puts at
 * {@code /releases/app-1.2.dmg} is the object {@code sites/<id>/releases/app-1.2.dmg} and is served at that same path.
 * Nothing here is a database row per file, and the files never live in a folder a container owns, so the proxy reads
 * them as Hohenheim and an upload never runs anything. The limit is counted over the stored objects themselves
 * ({@link #usedBytes}), never a disk measure, so an S3 storage counts the same way.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SiteFiles {

    /** The storage name of the installation's own site files, a local folder following its setting. */
    public static final String STORAGE = "hohenheim-site-files";

    /** How long a counted usage is trusted before the stored objects are counted again. */
    private static final Duration USAGE_TTL = Duration.ofMinutes(1);

    private record Usage(long bytes, @NonNull Instant counted) {}

    private static final Map<Integer, Usage> USAGE = BlastThread.createConcurrentHashMap();

    private SiteFiles() {
    }

    /** @return the folder key every file of the site is stored under, without the trailing slash */
    public static @NonNull String prefixOf(int siteId) {
        return "sites/" + siteId;
    }

    /**
     * The prefix every key of the site's files starts with: its folder and the slash, what a listing of its keys asks.
     *
     * AIDEV-NOTE: a storage lists by string prefix, so the bare folder ({@link #prefixOf}) also names
     * {@code sites/<id>0/}, another site's files; a move listing that took site 29's files with site 2's and removed
     * them from the old storage.
     */
    public static @NonNull String keysOf(int siteId) {
        return prefixOf(siteId) + "/";
    }

    /** @return whether the site's settings say it serves its own files */
    public static boolean servesOwnFiles(@Nullable Map<String, Object> settings) {
        return RawValues.isOn(settings, StaticUpstreamKind.OWN_FILES);
    }

    /** @return whether the site is a static-files site serving its own files */
    public static boolean servesOwnFiles(@NonNull Row site) {
        return StaticUpstreamKind.ID.toString().equals(site.get(SiteModel.UPSTREAM_KIND))
            && servesOwnFiles(RawValues.map(site.get(SiteModel.SETTINGS)));
    }

    /** @return the name of the storage a site's own files live in */
    public static @NonNull String storageNameOf(@Nullable Map<String, Object> settings) {
        String named = RawValues.trimmed(settings == null ? null : settings.get(StaticUpstreamKind.STORAGE.getName()));
        return named.isEmpty() ? STORAGE : named;
    }

    /**
     * The storage a site's own files live in: this server's own site files, a mount its settings declare, or a storage
     * a person added (opened through the storage layer's declared source).
     *
     * @throws Violations {@code site_files_storage_missing} when the site names a storage this host does not know
     */
    public static @NonNull StorageAdapter storageOf(@Nullable Map<String, Object> settings) {
        String named = storageNameOf(settings);
        if (STORAGE.equals(named)) {
            return Storages.mountLocalFollowing(STORAGE, HohenheimSettings.Files.SITE_FILES_DIRECTORY, null);
        }
        try {
            return Storages.get(named);
        } catch (IllegalStateException unknown) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("site_files_storage_missing")
                .withArg("storage", named));
        }
    }

    /** @return the tree the proxy serves a site's own files from, under the settings it is dialled with */
    public static @NonNull StoredFileTree servedTree(int siteId, @Nullable Map<String, Object> settings) {
        return new StoredFileTree(storageOf(settings), prefixOf(siteId));
    }

    /**
     * The live static-files site that serves its own files.
     *
     * @throws Violations {@code site_files_off} for any other site, a trashed or absent one included
     */
    public static @NonNull Row requireSite(int siteId) {
        Row site = Models.get(SiteModel.class).findById(siteId);
        if (site == null || !servesOwnFiles(site)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("site_files_off"));
        }
        return site;
    }

    /** @return the tree of the site's own files, rooted at "/" */
    static @NonNull StoredFileTree treeOf(int siteId) {
        Row site = requireSite(siteId);
        return new StoredFileTree(storageOf(RawValues.map(site.get(SiteModel.SETTINGS))), prefixOf(siteId));
    }

    /** At most this many files and folders are suggested for one typed path. */
    private static final int SUGGESTIONS = 20;

    /**
     * The site's own files and folders whose path starts with what was typed, read in the folder it names: folders
     * first, each ending in a slash, at most {@link #SUGGESTIONS}.
     *
     * @return nothing for a reader the Files tab would refuse, a site without its own files or a folder not there
     */
    public static @NonNull List<DataItem> suggestions(int siteId, @Nullable String typed) {
        String text = typed == null || typed.isBlank() ? "/" : typed.strip();
        text = text.startsWith("/") ? text : "/" + text;
        int slash = text.lastIndexOf('/');
        String start = text.substring(slash + 1);
        List<DataItem> items = new ArrayList<>();
        try {
            InstanceFiles.Listing listing = new InstanceFiles().list(FileRoot.site(siteId),
                slash == 0 ? "/" : text.substring(0, slash));
            for (InstanceFiles.Entry entry : listing.entries()) {
                if (items.size() == SUGGESTIONS) {
                    break;
                }
                if (entry.name().startsWith(start)) {
                    boolean folder = FilesystemNodeKind.DIRECTORY.name().equals(entry.kind());
                    items.add(DataItem.ofText(folder ? entry.path() + "/" : entry.path()));
                }
            }
        } catch (Violations refused) {
            return List.of();
        }
        return items;
    }

    /** @return the most bytes one site's files may hold together */
    public static long limitBytes() {
        return ByteUnit.MEGABYTE.bytes(HohenheimSettings.positiveOrDefault(HohenheimSettings.Files.MAX_SITE_FILES_MB));
    }

    /**
     * The bytes the site's files hold now, counted over its stored objects at most once a minute.
     *
     * AIDEV-NOTE: every write through the file manager forgets the count ({@link #forget}), so a person's own uploads
     * are judged against a fresh count; the minute only bounds what a write made outside it (another host serving
     * the same S3 storage) can add unseen.
     */
    static long usedBytes(@NonNull StoredFileTree tree, int siteId) throws IOException {
        Usage known = USAGE.get(siteId);
        if (known != null && known.counted().plus(USAGE_TTL).isAfter(Now.instant())) {
            return known.bytes();
        }
        long bytes = tree.storedBytes();
        USAGE.put(siteId, new Usage(bytes, Now.instant()));
        return bytes;
    }

    /**
     * Refuses a write that would take the site's files past the limit, else counts it as held until {@link #forget}.
     *
     * AIDEV-NOTE: the admitted size is added to the count at once, under this class's lock, so two uploads at the same
     * moment are judged together rather than each against the count without the other.
     *
     * @param incoming the size of the file written
     * @param replaced the stored object the write replaces, or null
     * @throws Violations {@code site_files_full}
     */
    static synchronized void requireRoom(@NonNull StoredFileTree tree, int siteId, long incoming,
                                         @Nullable StoredObject replaced) throws IOException {
        long limit = limitBytes();
        long after = usedBytes(tree, siteId) - (replaced == null ? 0 : replaced.size()) + incoming;
        if (after > limit) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("site_files_full").withArg("bytes", limit));
        }
        USAGE.put(siteId, new Usage(after, USAGE.get(siteId).counted()));
    }

    /** Forgets the counted usage of a site whose files changed. */
    static void forget(int siteId) {
        USAGE.remove(siteId);
    }
}
