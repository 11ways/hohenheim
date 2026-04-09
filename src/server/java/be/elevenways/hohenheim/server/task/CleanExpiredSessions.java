package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.SessionModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.protoblast.common.Blast;

import java.time.Instant;

/**
 * Deletes sessions past their expiry time.
 */
public class CleanExpiredSessions implements Runnable {

    @Override
    public void run() {
        try {
            var ds = HohenheimDatabase.datasource();
            var model = new SessionModel(ds);
            Instant now = Instant.now();

            long deleted = model.find()
                .where(SessionModel.EXPIRES_AT.lte(now))
                .delete();

            if (deleted > 0) {
                Blast.log("TASK: CleanExpiredSessions removed", deleted, "expired sessions");
            }
        } catch (Exception e) {
            Blast.log("TASK: CleanExpiredSessions failed:", e.getMessage());
        }
    }
}
