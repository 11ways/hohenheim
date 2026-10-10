package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.sftp.server.SftpConfig;
import be.elevenways.zenit.sftp.server.SftpServer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * The boot stage of Hohenheim's own SFTP server for app files: started from the {@code hohenheim.sftp} settings, over
 * {@link InstanceSftpRealm}, stopped at shutdown.
 *
 * AIDEV-NOTE: no role gate, deliberately: the server serves exactly what the Files tab serves, and every node that
 * renders the panel serves the Files tab (its lane reaches remote hosts through their Docker transport). A server that
 * is enabled but failed to start is not a refused boot: the failure is logged and kept, and the dashboard's attention
 * band names it (AttentionCollector), because a listener nobody can reach must find the operator.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class HohenheimSftp {

    private static final long MIB = 1024L * 1024L;

    private static @Nullable SftpServer running;
    private static @Nullable String failure;

    private HohenheimSftp() {
    }

    /** @return the configured port while SFTP is enabled, the one the SSH ban rule also covers; null when off */
    public static @Nullable Integer enabledPort() {
        if (!HohenheimSettings.isOn(HohenheimSettings.Sftp.ENABLED)) {
            return null;
        }
        int port = valueOf(HohenheimSettings.Sftp.PORT);
        return port > 0 && port <= 65535 ? port : null;
    }

    /** @return the address the Files tab tells people to connect to, null to show the page's own host */
    public static @Nullable String publicHost() {
        String host = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Sftp.PUBLIC_HOST);
        return host == null || host.isBlank() ? null : host.strip();
    }

    /** @return the server's settings, the spool under the data path */
    public static @NonNull SftpConfig configured() {
        String bind = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Sftp.BIND_ADDRESS);
        Path spool = Path.of(Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH))
            .resolve("sftp").resolve("spool");
        return new SftpConfig(bind == null || bind.isBlank() ? null : bind.strip(),
            valueOf(HohenheimSettings.Sftp.PORT),
            valueOf(HohenheimSettings.Sftp.MAX_SESSIONS),
            valueOf(HohenheimSettings.Sftp.MAX_SESSIONS_PER_ACCOUNT),
            Duration.ofMinutes(valueOf(HohenheimSettings.Sftp.IDLE_TIMEOUT_MINUTES)),
            valueOf(HohenheimSettings.Sftp.MAX_FILE_MB) * MIB,
            spool, SftpConfig.FAILED_LOGINS);
    }

    /**
     * Starts the server when SFTP is enabled; idempotent, a running server is replaced. A failure to start is kept
     * for {@link #failure()} and logged, never thrown.
     */
    public static synchronized void startIfEnabled() {
        stop();
        failure = null;
        if (!HohenheimSettings.isOn(HohenheimSettings.Sftp.ENABLED)) {
            return;
        }
        try {
            SftpConfig config = configured();
            running = SftpServer.on(config).realm(new InstanceSftpRealm(config.maxFileBytes())).start();
            Blast.slog("hohenheim.sftp_started", Map.of("port", running.port(),
                "fingerprint", running.hostKeyFingerprint()));
        } catch (IOException | RuntimeException refused) {
            failure = HohenheimViolations.reasonOf(refused);
            Blast.slog("hohenheim.sftp_failed", Map.of("error", failure));
        }
    }

    /** Ends every session and stops listening; idempotent. */
    public static synchronized void stop() {
        SftpServer server = running;
        running = null;
        if (server != null) {
            server.stop();
        }
    }

    /** @return the running server, null while SFTP is off, failed or stopped */
    public static synchronized @Nullable SftpServer server() {
        return running;
    }

    /** @return why the enabled server did not start, null when it runs or was never asked to */
    public static synchronized @Nullable String failure() {
        return failure;
    }

    private static int valueOf(@NonNull SettingDefinition<Integer> setting) {
        Integer value = Zenit.SETTINGS_VALUES.getValue(setting);
        return value != null ? value : setting.getDefaultValue();
    }
}
