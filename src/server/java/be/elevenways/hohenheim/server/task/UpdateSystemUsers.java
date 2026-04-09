package be.elevenways.hohenheim.server.task;

import be.elevenways.protoblast.common.Blast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;

/**
 * Discovers system users from /etc/passwd.
 * Used for the per-site uid/gid selection in the admin UI.
 */
public class UpdateSystemUsers implements Runnable {

    public record SystemUser(String name, int uid, int gid) {}

    private static volatile List<SystemUser> systemUsers = List.of();

    @Override
    public void run() {
        List<SystemUser> users = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader("/etc/passwd"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length < 4) continue;

                String name = parts[0];
                int uid, gid;
                try {
                    uid = Integer.parseInt(parts[2]);
                    gid = Integer.parseInt(parts[3]);
                } catch (NumberFormatException e) {
                    continue;
                }

                // Skip system users with uid < 1000 (except root and nobody)
                if (uid > 0 && uid < 1000 && !"nobody".equals(name)) continue;

                users.add(new SystemUser(name, uid, gid));
            }
        } catch (Exception e) {
            Blast.log("TASK: UpdateSystemUsers failed:", e.getMessage());
        }

        systemUsers = List.copyOf(users);
        Blast.log("TASK: UpdateSystemUsers found", users.size(), "users");
    }

    public static List<SystemUser> getSystemUsers() {
        return systemUsers;
    }
}
