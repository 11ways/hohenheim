package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.ServerZenitRuntime;

/**
 * Server entry point for Hohenheim.
 */
public class ServerMain {

    private static ProxyServer proxyServer;

    public static void main(String[] args) {
        // Register site types first (before SiteModel's RegistryEnumField is used)
        SiteTypes.register();

        HohenheimEndpoints.init();
        HohenheimDatabase.init();
        HohenheimHandlers.init();

        ServerZenitRuntime.main(args);
        Zenit.getHawkeye().setClientScriptLocation("/hohenheim.js");

        proxyServer = new ProxyServer();
        proxyServer.start();
    }

    public static ProxyServer getProxyServer() {
        return proxyServer;
    }
}
