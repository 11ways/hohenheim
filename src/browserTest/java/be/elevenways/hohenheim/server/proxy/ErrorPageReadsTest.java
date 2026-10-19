package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.generation.ModelGenerationModel;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.microcopy.server.TranslationModel;
import be.elevenways.zenit.test.support.DatasourceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proxy's own error pages render through zenit's server render lifecycle: a page validates the translation catalog
 * once, however many catalog words it shows, as a request render does.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class ErrorPageReadsTest {

    private static final String UNKNOWN_HOST = "nobody.error-page.test";

    private static ProxyServer proxy;
    private static String previousMessage;

    @BeforeAll
    static void boot() throws Exception {
        ProxyTestSupport.bootRuntime();
        previousMessage = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.NOT_FOUND_MESSAGE);
        proxy = ProxyTestSupport.startProxy();
    }

    @AfterAll
    static void stop() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.NOT_FOUND_MESSAGE, previousMessage);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    @Test
    void anErrorPageValidatesItsTranslationsOncePerRenderHoweverManyWordsItShows() throws Exception {
        int port = ProxyTestSupport.httpPort(proxy);
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Models.get(SiteModel.class)
            .getResolvedDatasource());

        // 1. The operator's own not-found message: the page shows one catalog word, its title, beside that literal.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.NOT_FOUND_MESSAGE, "Nothing lives here.");
        ProxyTestSupport.rawRequest(port, UNKNOWN_HOST, "/");
        probe.reset();
        String own = ProxyTestSupport.rawRequest(port, UNKNOWN_HOST, "/");
        List<Integer> oneWord = List.of(probe.queriesAgainst(ModelGenerationModel.MODEL_ID),
            probe.queriesAgainst(TranslationModel.MODEL_ID));
        assertThat(own).as("step 1: the not-found page with the operator's message")
            .contains(" 404 ").contains("Nothing lives here.").contains(UNKNOWN_HOST);

        // 2. The localized default: the page shows two catalog words, its title and its message.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.NOT_FOUND_MESSAGE, null);
        ProxyTestSupport.rawRequest(port, UNKNOWN_HOST, "/");
        probe.reset();
        String localized = ProxyTestSupport.rawRequest(port, UNKNOWN_HOST, "/");
        List<Integer> twoWords = List.of(probe.queriesAgainst(ModelGenerationModel.MODEL_ID),
            probe.queriesAgainst(TranslationModel.MODEL_ID));
        assertThat(localized).as("step 2: the not-found page in its own words")
            .contains(" 404 ").doesNotContain("Nothing lives here.").contains(UNKNOWN_HOST);

        // 3. THE ASSERTION: one validation per render, and the translation reads stay flat as the words grow.
        assertThat(oneWord.getFirst()).as("step 3: one word, one validation (%s, %s)", oneWord, twoWords)
            .isEqualTo(1);
        assertThat(twoWords).as("step 3: two words read exactly what one word read").isEqualTo(oneWord);
    }
}
