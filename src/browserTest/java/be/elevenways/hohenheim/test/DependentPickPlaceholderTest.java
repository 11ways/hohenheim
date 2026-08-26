package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.microcopy.MicrocopySeed;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server paint of a sibling-narrowed relation picker speaks the resolved
 * placeholder, never its microcopy key.
 */
class DependentPickPlaceholderTest extends HohenheimTestBase {

    @Test
    void instanceCreateFormPaintsResolvedPickerPlaceholders() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/admin/instances/new"))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("step 1: the create form renders").isEqualTo(200);
        // The inline microcopy seed lists every key the render RESOLVED; only the
        // rendered markup can say whether a picker painted one.
        String html = MicrocopySeed.withoutSeed(response.body());
        assertThat(html).as("step 2: no picker paints its raw microcopy key")
            .doesNotContain("relation_unresolved");
        assertThat(html).as("step 3: the narrowed pickers name their sibling")
            .contains("first");
    }
}
