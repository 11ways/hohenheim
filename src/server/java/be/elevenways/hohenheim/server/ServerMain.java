package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.ServerZenitRuntime;

/**
 * Server entry point for Hohenheim.
 */
public class ServerMain {

    public static void main(String[] args) {
        HohenheimEndpoints.init();
        HohenheimDatabase.init();
        HohenheimHandlers.init();

        ServerZenitRuntime.main(args);
        Zenit.getHawkeye().setClientScriptLocation("/hohenheim.js");
    }
}
