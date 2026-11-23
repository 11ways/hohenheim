package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.LinkedServerCodeModel;
import be.elevenways.hohenheim.server.MachineName;
import be.elevenways.protoblast.testsupport.browser.BoundedAssertions;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.model.Models;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Servers page in the browser: the Servers cluster with its Hosts tab, this server as the first row, the Add a
 * Hohenheim server form, the join code shown once in the card under the list, and this server named after its machine
 * while its name setting is empty.
 *
 * AIDEV-NOTE: it writes a screenshot of the page, the add form and the join code to build/linked-servers-screenshots,
 * the evidence a landing compares with the board screens (FO119: Servers, Server-Add, Server-Join-Code).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class LinkedServersBrowserTest extends HohenheimTestBase {

    /** Relative to the gradle test worker's working directory, the project root. */
    private static final Path OUT = Paths.get("build", "linked-servers-screenshots");
    private static String nameBefore;

    @BeforeAll
    static void nameThisServer() {
        nameBefore = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Cluster.NAME);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Cluster.NAME, "Robbedoes");
    }

    @AfterAll
    static void restoreName() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Cluster.NAME, nameBefore);
        Models.get(LinkedServerCodeModel.class).find().delete();
    }

    @Test
    void theServersPageAddsAServerAndShowsAJoinCodeOnceJourney() {
        page.setViewportSize(1440, 900);
        // 1. The sidebar's Servers lands on the linked servers, under the cluster's heading, with the Hosts tab.
        navigateToApp("/admin/" + HohenheimSlugs.Cluster.CLUSTER);
        waitForHydration();
        page.waitForCondition(() -> page.url().endsWith("/admin/" + HohenheimSlugs.LINKED_SERVERS));
        assertThat(page.locator("h1").first().textContent()).as("step 1: the cluster heads the page")
            .contains("Servers");
        String tabs = page.locator("[data-cms-cluster-tabs]").first().textContent();
        assertThat(tabs).as("step 1: the Servers and Hosts tabs").contains("Servers").contains("Hosts");

        // 2. This server is the first row: its name, "This server" in muted text under it (never a badge), reachable,
        //    under the column head "Server"; the list repeats no sentence of the page's own; an unmanaged server
        //    says so.
        String content = page.content();
        assertThat(content).as("step 2: this server's row").contains("Robbedoes").contains("This server")
            .contains("Reachable");
        assertThat(page.locator("[data-cms-second-line]").first().textContent()).as("step 2: the muted line")
            .contains("This server");
        assertThat(page.locator("pl-badge").filter(new Locator.FilterOptions().setHasText("This server")).count())
            .as("step 2: no badge says it").isZero();
        assertThat(page.locator("pl-table-head[data-column='name']").textContent())
            .as("step 2: the first column's head").contains("Server").doesNotContain("Name");
        assertThat(content).as("step 2: the page's sub line once, no list lead repeating it")
            .doesNotContain("The Hohenheim servers that work together.");
        assertThat(content).as("step 2: the card says nobody manages this server")
            .contains("No other Hohenheim manages this server.");
        shoot("servers");

        // 3. Add a Hohenheim server opens its form: name, address and the join code with where to get it.
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add a Hohenheim server")).first().click();
        // An operation asking input opens plumage's alert dialog (as zenit-cms ActionInputBrowserTest finds it).
        Locator form = page.locator(".pl-alertdialog-modal[data-open]");
        BoundedAssertions.assertThat(form).isVisible();
        // The dialog opens before its form arrives ("Loading the form..."): read the fields once they stand.
        BoundedAssertions.assertThat(form).containsText("It is valid for 10 minutes.");
        String fields = form.textContent();
        assertThat(fields).as("step 3: the three fields").contains("Name").contains("Address").contains("Join code");
        assertThat(fields).as("step 3: where the code comes from").contains("It is valid for 10 minutes.");
        // The picture waits for the dialog's and its backdrop's opening transitions to end: mid-fade, the page behind
        // shines through the dialog.
        waitForPageFunction("() => [...document.querySelectorAll('.pl-alertdialog-modal[data-open],"
            + " .pl-alertdialog-overlay')].every(e => e.getAnimations().length === 0)");
        shoot("server-add");
        form.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Cancel")).click();
        BoundedAssertions.assertThat(form).isHidden();

        // 4. Let another Hohenheim manage this server, a button beside Add, shows the code in the card under the
        //    list, grouped in fours with Copy and until when it is valid; a reload shows it no more, the card then
        //    says one waits.
        assertThat(page.locator("[data-cms-header-overflow]").count())
            .as("step 4: no More menu holds a header action").isZero();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
            .setName("Let another Hohenheim manage this server")).click();
        Locator shown = page.locator("[data-hh-join-code]");
        BoundedAssertions.assertThat(shown).isVisible();
        assertThat(shown.textContent()).as("step 4: the code, once, with until when it is valid")
            .containsPattern("[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}").contains("Shown once, valid until");
        assertThat(shown.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Copy")).count())
            .as("step 4: the code can be copied").isPositive();
        shown.scrollIntoViewIfNeeded();
        shoot("server-join-code");
        navigateToApp("/admin/" + HohenheimSlugs.LINKED_SERVERS);
        waitForHydration();
        assertThat(page.locator("[data-hh-join-code]").count()).as("step 4: shown once only").isZero();
        assertThat(page.content()).as("step 4: the card says a code waits").contains("A join code is waiting");

        // 5. With its name setting empty this server is named after its machine.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Cluster.NAME, null);
        navigateToApp("/admin/" + HohenheimSlugs.LINKED_SERVERS);
        waitForHydration();
        assertThat(page.content()).as("step 5: the machine's name, the setting's no more")
            .contains(MachineName.firstLabel()).doesNotContain("Robbedoes");
    }

    private void shoot(String name) {
        page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve(name + ".png")).setFullPage(true));
    }
}
