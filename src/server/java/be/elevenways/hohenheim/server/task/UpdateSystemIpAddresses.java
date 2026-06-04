package be.elevenways.hohenheim.server.task;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;

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

    public static List<ScheduleDeclaration> defaultSchedules() {
        return List.of(ScheduleDeclaration.bootAndCron("8 * * * *"));
    }

    @Override
    public void executor(TaskContext ctx) {
        discover();
    }

    /** Scan network interfaces and refresh the cached local-address list. */
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
        } catch (Exception e) {
            Blast.log("TASK: UpdateSystemIpAddresses failed:", e.getMessage());
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
