package be.elevenways.hohenheim;

import be.elevenways.plumage.component.BrowserSortableBridge;
import be.elevenways.plumage.component.BrowserTableSelectionBridge;
import be.elevenways.plumage.component.BrowserTerminalBridge;
import be.elevenways.plumage.component.SortableFunctions;
import be.elevenways.plumage.component.TableSelectionFunctions;
import be.elevenways.plumage.component.TerminalFunctions;
import be.elevenways.zenit.client.ClientZenitRuntime;
import be.elevenways.zenit.common.Zenit;

/**
 * Browser entry point for Hohenheim. TeaVM compiles this to /cms.js.
 * The browser has no MODELS/MODULES boot stage, so models and record
 * sources MUST register before the runtime hydrates.
 */
public class ClientMain {

    public static void main(String[] args) throws Exception {
        HohenheimModels.registerAll();
        HohenheimSources.register();
        // Plumage browser bridges: a missing bridge is silent (terminal never
        // connects, sortable never dispatches reorder), so install them all.
        TerminalFunctions.setBridge(new BrowserTerminalBridge());
        SortableFunctions.setBridge(new BrowserSortableBridge());
        TableSelectionFunctions.setBridge(new BrowserTableSelectionBridge());
        ClientZenitRuntime.main(null);
        Zenit.ROOT_STAGE.launch();
    }
}
