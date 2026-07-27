package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stack admin surface end to end: every page RENDERS (the service and file
 * forms carry RelationPicks whose record sources come from the CMS auto-glue --
 * a missing source is a 500, and nothing else covers these pages), records
 * validate, and deleting cascades to the child rows that have no FK cascade.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StackAdminTest extends HohenheimTestBase {

    private static Integer stackId;
    private static Integer serviceId;

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void stackListAndFormRender() throws Exception {
        navigateToApp("/admin/stacks");
        waitForHydration();
        assertThat(page.locator("body").textContent()).doesNotContain("Internal Server Error");

        navigateToApp("/admin/stacks/new");
        waitForHydration();
        assertThat(page.locator("form").count()).isGreaterThan(0);

        var response = postForm("/admin/stacks/new",
            "name=admin-test-stack&enabled=false&enabled=true&server_name=local&subnet=172.31.9.0/24");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row stack = Models.get(StackModel.class).find()
            .where(StackModel.NAME.eq("admin-test-stack")).first();
        assertThat(stack).isNotNull();
        stackId = stack.get(StackModel.ID);
    }

    @Test
    @Order(2)
    void malformedSubnetIsRefused() throws Exception {
        postForm("/admin/stacks/new",
            "name=bad-subnet-stack&enabled=false&enabled=true&server_name=local&subnet=not-a-cidr");

        assertThat(Models.get(StackModel.class).find()
            .where(StackModel.NAME.eq("bad-subnet-stack")).count())
            .as("a malformed subnet must fail the form, not the deploy worker")
            .isEqualTo(0);
    }

    /** The service form's RelationPick needs a registered record source for the stack model. */
    @Test
    @Order(3)
    void serviceFormRendersAndCreates() throws Exception {
        navigateToApp("/admin/stack-services/new?stack_id=" + stackId);
        waitForHydration();
        assertThat(page.locator("form").count()).isGreaterThan(0);

        var response = postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=web&enabled=false&enabled=true&image=alpine%3Alatest"
            + "&command=&restart_policy=no"
            + "&mounts.0.type=volume&mounts.0.name=data&mounts.0.container_path=%2Fdata"
            + "&ports.0.container_port=80&ports.0.host_port=8099&ports.0.protocol=tcp");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row service = Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("web")).first();
        assertThat(service).isNotNull();
        serviceId = service.get(StackServiceModel.ID);

        navigateToApp("/admin/stack-services/" + serviceId);
        waitForHydration();
        assertThat(page.content()).contains("alpine:latest");
    }

    @Test
    @Order(4)
    void servicesTabRendersLiveState() {
        navigateToApp("/admin/stacks/" + stackId + "/page/services");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("web");
        // The container does not exist, so the state badge renders the LOCALIZED
        // "missing" label rather than the raw state token.
        assertThat(body).contains("Missing");
        assertThat(body).doesNotContain("- Services");
    }

    @Test
    @Order(5)
    void deploymentsTabRenders() {
        navigateToApp("/admin/stacks/" + stackId + "/page/deployments");
        waitForHydration();
        assertThat(page.locator("body").textContent()).doesNotContain("Internal Server Error");
    }

    /** The file form's RelationPick targets the SERVICE model's record source. */
    @Test
    @Order(6)
    void fileFormRendersAndValidatesAgainstMounts() throws Exception {
        navigateToApp("/admin/stack-files/new?stack_service_id=" + serviceId);
        waitForHydration();
        assertThat(page.locator("form").count()).isGreaterThan(0);

        // /data is a volume mount, so a file there would be shadowed at container start.
        postForm("/admin/stack-files/new",
            "stack_service_id=" + serviceId + "&container_path=%2Fdata%2Fapp.conf"
            + "&content=secret%3D1&mode=0600");
        assertThat(Models.get(StackFileModel.class).find()
            .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).count())
            .as("a file under a volume mount must be refused")
            .isEqualTo(0);

        var accepted = postForm("/admin/stack-files/new",
            "stack_service_id=" + serviceId + "&container_path=%2Fetc%2Fapp.conf"
            + "&content=secret%3D1&mode=0600");
        assertThat(accepted.statusCode()).isIn(200, 302, 303);
        assertThat(Models.get(StackFileModel.class).find()
            .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).count()).isEqualTo(1);
    }

    @Test
    @Order(7)
    void duplicateHostPortAcrossServicesIsRefused() throws Exception {
        postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=other&enabled=false&enabled=true&image=alpine%3Alatest"
            + "&command=&restart_policy=no"
            + "&ports.0.container_port=80&ports.0.host_port=8099&ports.0.protocol=tcp");

        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("other")).count())
            .as("two services cannot publish the same host port")
            .isEqualTo(0);
    }

    @Test
    @Order(8)
    void unknownDependencyIsRefused() throws Exception {
        postForm("/admin/stack-services/new",
            "stack_id=" + stackId + "&name=dependent&enabled=false&enabled=true&image=alpine%3Alatest"
            + "&command=&restart_policy=no"
            + "&depends_on.0.service=nope&depends_on.0.condition=started");

        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.NAME.eq("dependent")).count())
            .as("a dependency naming no sibling service can never be satisfied")
            .isEqualTo(0);
    }

    @Test
    @Order(9)
    void deletingTheStackCascadesToServicesAndFiles() throws Exception {
        var response = postForm("/admin/stacks/" + stackId + "/delete", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        assertThat(Models.get(StackModel.class).findById(stackId)).isNull();
        assertThat(Models.get(StackServiceModel.class).find()
            .where(StackServiceModel.STACK_ID.eq(stackId)).count()).isEqualTo(0);
        assertThat(Models.get(StackFileModel.class).find()
            .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).count())
            .as("config files (encrypted secrets) must not outlive their stack")
            .isEqualTo(0);
    }
}
