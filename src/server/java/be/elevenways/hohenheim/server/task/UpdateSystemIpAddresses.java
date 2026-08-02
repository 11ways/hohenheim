package be.elevenways.hohenheim.server.task;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Discovers local IP addresses on the system (for listen_on validation and the settings UI),
 * refreshed once at boot and hourly via the zenit task scheduler.
 */
public class UpdateSystemIpAddresses extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Discover local IP addresses";

    private static volatile List<String> localAddresses = List.of();

    @Override
    public @NonNull UpdateSystemIpAddresses newTask() {
        return new UpdateSystemIpAddresses();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.bootAndCron("8 * * * *")),
            HohenheimRoles.Role.PROXY, HohenheimRoles.Role.FIREWALL);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        discover();
    }

    /**
     * Scan network interfaces and refresh the cached local-address list.
     *
     * @throws java.io.UncheckedIOException when interface enumeration fails --
     *         the task system records the FAILED run; the previous cached list
     *         stays in effect, which beats silently degrading to loopback-only
     */
    public static void discover() {
        List<String> addresses = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;

                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress()) {
                        addresses.add(addr.getHostAddress());
                    }
                }
            }
        } catch (java.net.SocketException e) {
            throw new java.io.UncheckedIOException("Network interface enumeration failed", e);
        }

        // Always include loopback
        addresses.add("127.0.0.1");
        addresses.add("::1");

        localAddresses = List.copyOf(addresses);
        Blast.log("TASK: UpdateSystemIpAddresses found", addresses.size(), "addresses");
    }

    public static List<String> getLocalAddresses() {
        return localAddresses;
    }
}
