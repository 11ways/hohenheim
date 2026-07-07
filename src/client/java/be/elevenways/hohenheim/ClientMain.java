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
 * Models register at class-load (generated BlastAutoLoadInit) and record
 * sources via the HohenheimSources module, which ClientZenitRuntime inits
 * pre-hydration -- no manual registration here.
 */
public class ClientMain {

    public static void main(String[] args) throws Exception {
        // Plumage browser bridges: a missing bridge is silent (terminal never
        // connects, sortable never dispatches reorder), so install them all.
        TerminalFunctions.setBridge(new BrowserTerminalBridge());
        SortableFunctions.setBridge(new BrowserSortableBridge());
        TableSelectionFunctions.setBridge(new BrowserTableSelectionBridge());
        ClientZenitRuntime.main(null);
        Zenit.ROOT_STAGE.launch();
    }
}
