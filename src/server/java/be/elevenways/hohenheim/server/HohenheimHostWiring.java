package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.hohenheim.server.security.HohenheimSecurity;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.ZenitModule;

/**
 * The one owner of every wiring an incoming request depends on: the client
 * script location, all HTTP/WebSocket endpoint handlers, the two CMS panels and
 * the native security engine, installed at the MODULES boot stage.
 *
 * AIDEV-NOTE: a discovered {@link ZenitModule} ON PURPOSE, exactly like
 * {@link HohenheimWriteHooks}, because the ordering is the whole point. This
 * used to sit AFTER {@code ServerZenitRuntime.main(args)} in
 * {@code ServerMain.main}, which returns only once the STARTHTTP stage (weight
 * 50) has already bound the listener -- so there was a live window in which
 * {@code /ws/dev-tunnel} and {@code /ws/terminal} still carried their
 * placeholder factory (zenit answers that with
 * "WebSocket handler factory returned null"), every HTTP endpoint below was
 * unhandled, and no panel or resource was registered, which makes every
 * {@code /admin/...} URL a 404. MODULES (200) completes before STARTHTTP (50)
 * binds, and MODELS (300) already ran, which is the constraint the old comment
 * named: the panels' resources resolve model singletons. Discovery also means
 * the test harness cannot wire a DIFFERENT order than production -- it used to,
 * which is why the split could survive a green suite.
 *
 * @author Jelle De Loecker
 */
public final class HohenheimHostWiring implements ZenitModule {

    @Override
    public void init() {
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");

        // Endpoint handlers, including the real DEV_TUNNEL and PROCESS_TERMINAL
        // WebSocket handler factories that replace the declaration placeholders.
        HohenheimHandlers.init();

        // The admin panel and the delegated operator panel: registering them is
        // what makes the generic /{panel}/... CMS routes resolve to anything.
        new HohenheimPanel();
        new ManagePanel();

        // Native security engine: local event sink, event vocabulary, own-IP
        // guard, nftables setup + resync, ban-cache warmup. Before the first
        // request rather than after it, so no request escapes the ban cache.
        HohenheimSecurity.boot();
    }
}
