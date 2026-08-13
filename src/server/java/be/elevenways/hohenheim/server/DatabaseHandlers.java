package be.elevenways.hohenheim.server;

import be.elevenways.domino.common.DominoFile;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Managed databases: dump download + restore upload.
 */
final class DatabaseHandlers {

    private DatabaseHandlers() {
    }

    static void init() {
        DatabaseService databaseService = new DatabaseService();

        HohenheimEndpoints.DATABASES_BACKUP.setHandler(conduit -> {
            // AIDEV-NOTE: this is a side-effecting GET (it execs a full dump and records an
            // activity row). The route stays GET because it is a file DOWNLOAD -- the CMS
            // row-action download mechanism is a link and CmsActionResult cannot stream, so
            // a POST twin of the RESTORE endpoint is not reachable without a chain change.
            // Instead a Fetch-Metadata check refuses a CROSS-SITE drive: an <img src>/auto-
            // navigation from an attacker page rides the victim's session cookie and would
            // otherwise force a production dump + a false audit row attributing it to them.
            // A same-origin link click (same-origin/same-site) and a top-level navigation
            // (none) are allowed; a header-less non-browser client is not the CSRF victim.
            if (HandlerSupport.isCrossSiteBrowserRequest(conduit)) {
                conduit.notFound();
                return null;
            }
            String name = conduit.getParameter(HohenheimEndpoints.DATABASE_NAME);
            if (Models.get(DatabaseModel.class).find()
                    .where(DatabaseModel.NAME.eq(name)).first() == null) {
                // Absence answers exactly like refusal below -- see the note there.
                conduit.notFound();
                return null;
            }
            DatabaseService.BackupDownload dump;
            try {
                dump = databaseService.backupDownload(name);
            } catch (Violations refused) {
                // AIDEV-NOTE: absence and refusal are ONE answer here. The URL is keyed by
                // NAME, so distinguishing them would turn this endpoint into an oracle
                // over every other tenant's database names -- exactly what the /manage
                // list scope refuses to leak. 404, never 403, never a redirect that says
                // which panel the caller does not belong to.
                conduit.notFound();
                return null;
            } catch (IOException e) {
                // A real failure for a caller who PASSED the gate (daemon down, no engine
                // instance yet): they may know, so this stays the operator's redirect.
                Blast.log("DB: backup of", name, "failed -", e.getMessage());
                return HandlerSupport.redirect(CmsRoutes.list(HandlerSupport.ADMIN, "databases"));
            }
            ActivityLog.record(Models.get(DatabaseModel.class), name, "backup_downloaded", name);
            HandlerSupport.download(conduit, dump.contentType(), dump.filename(), dump.content());
            return null;
        });

        HohenheimEndpoints.DATABASES_RESTORE.setHandler(conduit -> {
            String name = conduit.getParameter(HohenheimEndpoints.DATABASE_NAME);
            RouteTarget restorePage = restorePageTarget(name);
            if (!(conduit.getFormData().get("dump") instanceof DominoFile file) || file.getSize() == 0) {
                HohenheimFlash.error(conduit, databaseMessage("dump_required", name));
                return HandlerSupport.redirect(restorePage);
            }
            try {
                Path temp = Files.createTempFile("hohenheim-restore-upload", null);
                try {
                    Files.write(temp, file.getBytes());
                    databaseService.restoreFromFile(name, temp);
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (UnsupportedOperationException e) {
                Blast.log("DB: restore of", name, "rejected -", e.getMessage());
                HohenheimFlash.error(conduit, databaseMessage("restore_unsupported", name));
                return HandlerSupport.redirect(restorePage);
            } catch (IOException e) {
                Blast.log("DB: restore of", name, "failed -", e.getMessage());
                HohenheimFlash.error(conduit, databaseMessage("restore_failed", name));
                return HandlerSupport.redirect(restorePage);
            }
            ActivityLog.record(Models.get(DatabaseModel.class), name, ActivityLog.ACTION_RESTORE, name);
            HohenheimFlash.success(conduit, databaseMessage("restored", name));
            return HandlerSupport.redirect(restorePage);
        });
    }

    /** A managed-database outcome message, named after the database it is about. */
    private static Microcopy databaseMessage(String key, String name) {
        return Microcopy.of(key).withFilter("scope", "database_tab").withArg("name", name);
    }

    /** The CMS restore tab for a named database (falls back to the list when unknown). */
    private static @NonNull RouteTarget restorePageTarget(String name) {
        Row row = Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq(name)).first();
        if (row == null) {
            return CmsRoutes.list(HandlerSupport.ADMIN, "databases");
        }
        return CmsRoutes.subpage(HandlerSupport.ADMIN, "databases", row.get(DatabaseModel.ID), "restore");
    }
}
