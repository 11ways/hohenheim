package be.elevenways.hohenheim;

import be.elevenways.plumage.component.BrowserTerminalBridge;
import be.elevenways.plumage.component.TerminalFunctions;
import be.elevenways.zenit.client.ClientZenitRuntime;
import be.elevenways.zenit.common.Zenit;

/**
 * Browser entry point for Hohenheim. TeaVM compiles this to /cms.js.
 * Models register at class-load (generated BlastAutoLoadInit) and record
 * sources via the HohenheimSources module, which ClientZenitRuntime inits
 * pre-hydration -- no manual registration here.
 */
public class ClientMain {

    public static void main(String[] args) throws Exception {
        // The terminal bridge must install before hydration: without it the
        // terminal renders but never connects.
        TerminalFunctions.setBridge(new BrowserTerminalBridge());
        ClientZenitRuntime.main(null);
        Zenit.ROOT_STAGE.launch();
    }
}
