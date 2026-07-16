package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimSettings;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** DNS-01 publisher backed by an operator-owned executable hook. */
public final class CommandDnsTxtPublisher implements DnsTxtPublisher {

    public static final String ID = "command";
    private static final long TIMEOUT_SECONDS = 60;

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
        String command = HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.DNS_HOOK_COMMAND);
        return command != null && !command.isBlank();
    }

    private static void run(String action, DnsTxtRecord record) throws Exception {
        String command = HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.DNS_HOOK_COMMAND);
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("DNS hook command is not configured");
        }
        Process process = new ProcessBuilder(command.trim(), action, record.name(), record.value())
            .redirectErrorStream(true)
            .start();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("DNS hook timed out during " + action);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("DNS hook " + action + " failed (exit "
                + process.exitValue() + ")" + (output.isEmpty() ? "" : ": " + output));
        }
    }
}
