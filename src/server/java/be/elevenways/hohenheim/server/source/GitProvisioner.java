package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Entry point for git provisioning. Not a SiteTypeHandler -- wraps any type handler.
 */
public class GitProvisioner {

    private static final ScheduledExecutorService POLL_SCHEDULER =
        Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "git-poll");
            t.setDaemon(true);
            return t;
        });

    /**
     * Create a git-provisioned handler for a site.
     * Returns immediately (non-blocking). The actual clone/build runs async.
     */
    public static SiteRequestHandler createHandler(Row site, SiteTypeHandler typeHandler,
                                                    Map<String, Object> typeSettings,
                                                    Map<String, Object> sourceSettings,
                                                    int siteId) {
        File siteDir = getSiteDirectory(siteId);
        return new GitSiteRequestHandler(siteId, site, typeHandler, typeSettings,
            sourceSettings, siteDir);
    }

    /**
     * Delete the entire site directory (both slots + symlink).
     * Called only from the SITES_DELETE handler.
     */
    public static void deleteSiteDirectory(int siteId) {
        File siteDir = getSiteDirectory(siteId);
        if (siteDir.exists()) {
            deleteRecursive(siteDir);
            Blast.log("GIT: deleted repo directory for site", siteId);
        }
    }

    public static File getSiteDirectory(int siteId) {
        String dataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        return new File(dataPath, "git-repos/" + siteId);
    }

    static ScheduledExecutorService getPollScheduler() {
        return POLL_SCHEDULER;
    }

    private static void deleteRecursive(File dir) {
        if (dir == null || !dir.exists()) return;

        // Handle symlinks: don't follow, just delete
        if (Files.isSymbolicLink(dir.toPath())) {
            dir.delete();
            return;
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                deleteRecursive(f);
            }
        }
        dir.delete();
    }
}
