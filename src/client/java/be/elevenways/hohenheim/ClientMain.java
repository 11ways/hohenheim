package be.elevenways.hohenheim;

import be.elevenways.zenit.client.ClientZenitRuntime;
import be.elevenways.zenit.common.Zenit;

/**
 * Browser entry point for Hohenheim. TeaVM compiles this to /cms.js.
 * Models register at class-load (generated BlastAutoLoadInit), record
 * sources via the HohenheimSources module, which ClientZenitRuntime inits
 * pre-hydration, and plumage's terminal bridge installs itself at class-load
 * too -- no manual registration here.
 */
public class ClientMain {

    public static void main(String[] args) throws Exception {
        ClientZenitRuntime.main(null);
        Zenit.ROOT_STAGE.launch();
    }
}
