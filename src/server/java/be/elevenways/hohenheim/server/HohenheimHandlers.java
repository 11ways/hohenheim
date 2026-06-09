package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.server.handlers.AccessListHandlers;
import be.elevenways.hohenheim.server.handlers.AuditLogHandlers;
import be.elevenways.hohenheim.server.handlers.AuthProviderHandlers;
import be.elevenways.hohenheim.server.handlers.CertificateHandlers;
import be.elevenways.hohenheim.server.handlers.DashboardHandlers;
import be.elevenways.hohenheim.server.handlers.DatabaseHandlers;
import be.elevenways.hohenheim.server.handlers.DomainHandlers;
import be.elevenways.hohenheim.server.handlers.NotificationHandlers;
import be.elevenways.hohenheim.server.handlers.ProcessControlHandlers;
import be.elevenways.hohenheim.server.handlers.ServerHandlers;
import be.elevenways.hohenheim.server.handlers.SettingsHandlers;
import be.elevenways.hohenheim.server.handlers.SiteHandlers;
import be.elevenways.hohenheim.server.handlers.TestEndpointHandlers;
import be.elevenways.hohenheim.server.handlers.WebSocketHandlers;

/**
 * Server-side request handlers for Hohenheim endpoints.
 */
public class HohenheimHandlers {

    public static void init() {
        TestEndpointHandlers.init();
        DashboardHandlers.init();
        SiteHandlers.init();
        DomainHandlers.init();
        CertificateHandlers.init();
        SettingsHandlers.init();
        AuditLogHandlers.init();
        AccessListHandlers.init();
        AuthProviderHandlers.init();
        DatabaseHandlers.init();
        ServerHandlers.init();
        NotificationHandlers.init();
        ProcessControlHandlers.init();
        WebSocketHandlers.init();
    }
}
