package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * The site create form carries the type-discriminated settings sub-form:
 * every registered site type contributes a server-rendered variant, so
 * switching types swaps sub-forms client-side without a reload.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteTypeTest extends HohenheimTestBase {

    @Test
    @Order(1)
    void createFormOffersAllEightSiteTypes() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("hohenheim:proxy");
        assertThat(content).contains("hohenheim:node");
        assertThat(content).contains("hohenheim:alchemy");
        assertThat(content).contains("hohenheim:command");
        assertThat(content).contains("hohenheim:docker");
        assertThat(content).contains("hohenheim:static");
        assertThat(content).contains("hohenheim:redirect");
        assertThat(content).contains("hohenheim:dead");
    }

    @Test
    @Order(2)
    void perTypeSettingsVariantsAreServerRendered() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        // Distinctive per-type fields prove each variant sub-form travelled.
        String content = page.content();
        assertThat(content).contains("forward_host");    // proxy
        assertThat(content).contains("target_url");      // redirect
        assertThat(content).contains("script");          // node
        assertThat(content).contains("root_path");       // static
        assertThat(content).contains("start_command");   // command
        assertThat(content).contains("container_port");  // docker
    }

    @Test
    @Order(3)
    void nodeVariantCarriesEnvVarAndUserControls() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("environment_variables");
        assertThat(content).contains("use_ports");
        assertThat(content).contains("wait_for_ready");
        assertThat(content).contains("api_keys");
    }
}
