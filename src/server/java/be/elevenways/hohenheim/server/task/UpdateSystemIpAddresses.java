package be.elevenways.hohenheim.server.task;

import be.elevenways.protoblast.common.Blast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;

/**
 * Discovers local IP addresses on the system.
 * Used for listen_on validation and the settings UI.
 */
public class UpdateSystemIpAddresses implements Runnable {

    private static volatile List<String> localAddresses = List.of();

    @Override
    public void run() {
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
