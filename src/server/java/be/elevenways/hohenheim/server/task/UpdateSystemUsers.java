package be.elevenways.hohenheim.server.task;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;

import java.io.BufferedReader;
import java.io.FileReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers system users from /etc/passwd and reconciles them with the system_users table,
 * once at boot and hourly. Users that disappear from /etc/passwd get marked obsolete instead
 * of deleted so in-flight site references don't silently break.
 */
public class UpdateSystemUsers extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Discover system users";

    /** Record used when parsing /etc/passwd before we hit the DB. */
    private record ParsedUser(String name, int uid, int gid, String home, String gecos) {}

    public static List<ScheduleDeclaration> defaultSchedules() {
        return List.of(ScheduleDeclaration.bootAndCron("11 * * * *"));
    }

    @Override
    public void executor(TaskContext ctx) {
        reconcile();
    }

    /** Parse /etc/passwd and reconcile the system_users table. */
    public static void reconcile() {
        List<ParsedUser> parsed = parsePasswdFile();

        SystemUserModel model = Models.get(SystemUserModel.class);
        Instant now = Instant.now();
        Set<String> seen = new HashSet<>();

        for (ParsedUser pu : parsed) {
            seen.add(pu.name());
            Row existing = model.find().where(SystemUserModel.NAME.eq(pu.name())).first();
            if (existing == null) {
                Row row = model.createEmptyRow();
                row.set(SystemUserModel.NAME, pu.name());
                row.set(SystemUserModel.UID, pu.uid());
                row.set(SystemUserModel.GID, pu.gid());
                row.set(SystemUserModel.HOME, pu.home());
                row.set(SystemUserModel.GECOS, pu.gecos());
                row.set(SystemUserModel.OBSOLETE, false);
                row.set(SystemUserModel.LAST_SEEN_AT, now);
                model.save(row);
            } else {
                existing.set(SystemUserModel.UID, pu.uid());
                existing.set(SystemUserModel.GID, pu.gid());
                existing.set(SystemUserModel.HOME, pu.home());
                existing.set(SystemUserModel.GECOS, pu.gecos());
                existing.set(SystemUserModel.OBSOLETE, false);
                existing.set(SystemUserModel.LAST_SEEN_AT, now);
                model.save(existing);
            }
        }

        // Mark rows that didn't appear in this scan as obsolete — keeping them
        // in the table so sites still referencing them resolve to *something*.
        for (Row row : model.find().where(SystemUserModel.OBSOLETE.eq(false)).all()) {
            String name = row.get(SystemUserModel.NAME);
            if (!seen.contains(name)) {
                row.set(SystemUserModel.OBSOLETE, true);
                model.save(row);
            }
        }

        Blast.log("TASK: UpdateSystemUsers reconciled", parsed.size(), "users");
    }

    private static List<ParsedUser> parsePasswdFile() {
        List<ParsedUser> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/etc/passwd"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Fields: name:passwd:uid:gid:gecos:home:shell
                String[] parts = line.split(":", -1);
                if (parts.length < 6) continue;

                String name = parts[0];
                int uid, gid;
                try {
                    uid = Integer.parseInt(parts[2]);
                    gid = Integer.parseInt(parts[3]);
                } catch (NumberFormatException e) {
                    continue;
                }

                // Skip daemon/system accounts except root and nobody — they're never
                // the right choice for running user code and just clutter the dropdown.
                if (uid > 0 && uid < 1000 && !"nobody".equals(name)) continue;

                String gecos = parts[4];
                String home = parts[5];
                result.add(new ParsedUser(name, uid, gid, home, gecos));
            }
        } catch (Exception e) {
            Blast.log("TASK: UpdateSystemUsers failed:", e.getMessage());
        }
        return result;
    }
}
