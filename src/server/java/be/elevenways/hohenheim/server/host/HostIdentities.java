package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * THE minting of a host's client identity, whatever trust lane it is for: a tool writes a fresh private and public
 * half into a scratch directory, both land in the lane's slot on the host row in one recorded update, and the scratch
 * directory goes.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class HostIdentities {

    private HostIdentities() {
    }

    /** How one trust lane mints its identity. */
    public interface Minter {

        /** @return the command writing the private half to {@code privateHalf}, the public one to {@code publicHalf} */
        @NonNull Subprocess command(@NonNull Path privateHalf, @NonNull Path publicHalf, @NonNull String serverName);
    }

    /**
     * Mint the host's identity for this lane when it has none.
     *
     * @return true when one was made
     */
    public static boolean ensure(@NonNull Row server, @NonNull HostTrustSlot slot, @NonNull Runnable mint) {
        String existing = server.get(slot.clientPrivate());
        if (existing != null && !existing.isBlank()) {
            return false;
        }
        mint.run();
        return true;
    }

    /**
     * Mint a FRESH identity for this lane and store it on the host row.
     *
     * @param privateName the private half's file name in the scratch directory
     * @param publicName  the public half's file name in the scratch directory
     * @param trimPublic  whether the public half is stored trimmed (a one-line key), else verbatim (a PEM document)
     * @throws Violations {@code identity_generation_failed} with the tool's or the file system's reason
     */
    public static void rotate(@NonNull Row server, @NonNull HostTrustSlot slot, @NonNull String scratchPrefix,
                              @NonNull String privateName, @NonNull String publicName, boolean trimPublic,
                              @NonNull Minter minter) {
        String name = String.valueOf((Object) server.get(ServerModel.NAME));
        Path directory = null;
        try {
            directory = Files.createTempDirectory(scratchPrefix);
            Path privateHalf = directory.resolve(privateName);
            Path publicHalf = directory.resolve(publicName);
            ProcessOutcome result = minter.command(privateHalf, publicHalf, name).runChecked();
            if (!result.succeeded() || !Files.exists(privateHalf) || !Files.exists(publicHalf)) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("identity_generation_failed")
                    .withArg("detail", result.failureText()));
            }
            String privateKey = Files.readString(privateHalf, StandardCharsets.UTF_8);
            String publicText = Files.readString(publicHalf, StandardCharsets.UTF_8);
            String publicKey = trimPublic ? publicText.trim() : publicText;
            ActivityLog.withAction(ZenitActivityAction.UPDATE, "host_identity_rotated", () -> {
                server.set(slot.clientPrivate(), privateKey);
                server.set(slot.clientPublic(), publicKey);
                Models.get(ServerModel.class).save(server);
            });
            Blast.slog("hohenheim.host.identity_rotated", Map.of("server", name));
        } catch (IOException e) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("identity_generation_failed")
                .withArg("detail", String.valueOf(e.getMessage())));
        } finally {
            // Best effort: a scratch directory that survives holds a key the row already replaced.
            FileTrees.deleteQuietly(directory);
        }
    }
}
