package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.process.BoundedProcess;
import be.elevenways.zenit.common.Zenit;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.concurrent.TimeUnit;

/** DNS-01 publisher backed by an operator-owned executable hook. */
public final class CommandDnsTxtPublisher implements DnsTxtPublisher {

    /** The stored column's member, never a second spelling of it. */
    public static final String ID = CertificateModel.DNS_PUBLISHER_COMMAND;
    private static final long TIMEOUT_SECONDS = 60;
    private static final int OUTPUT_CAP_CHARS = 64 * 1024;

    @Override public @NonNull String id() { return ID; }

    @Override
    public void publish(@NonNull DnsTxtRecord record) throws Exception {
        run("present", record);
    }

    @Override
    public void cleanup(@NonNull DnsTxtRecord record) throws Exception {
        run("cleanup", record);
    }

    public static boolean isConfigured() {
        String command = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.DNS_HOOK_COMMAND);
        return command != null && !command.isBlank();
    }

    private static void run(String action, DnsTxtRecord record) throws Exception {
        String command = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.DNS_HOOK_COMMAND);
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("DNS hook command is not configured");
        }
        BoundedProcess.Result result = BoundedProcess.run(
            new ProcessBuilder(command.trim(), action, record.name(), record.value())
                .redirectErrorStream(true),
            TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS), OUTPUT_CAP_CHARS);
        if (result.timedOut()) {
            throw new IllegalStateException("DNS hook timed out during " + action);
        }
        String output = result.stdout().trim();
        if (!result.succeeded()) {
            throw new IllegalStateException("DNS hook " + action + " failed (exit "
                + result.exitCode() + ")" + (output.isEmpty() ? "" : ": " + output));
        }
    }
}
