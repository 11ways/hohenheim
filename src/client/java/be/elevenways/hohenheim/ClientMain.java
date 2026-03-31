package be.elevenways.hohenheim;

import be.elevenways.zenit.client.ClientZenitRuntime;
import be.elevenways.zenit.common.Zenit;

/**
 * Browser entry point for Hohenheim. TeaVM compiles this to JavaScript.
 */
public class ClientMain {

    public static void main(String[] args) throws Exception {
        ClientZenitRuntime.main(null);
        Zenit.ROOT_STAGE.launch();
    }
}
