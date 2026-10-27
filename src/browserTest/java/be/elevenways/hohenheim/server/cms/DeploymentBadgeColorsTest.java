package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.instance.InstanceDeploys;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deployment rows forward the shared classifier's roles, hues and unknown-key honesty to both templates.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class DeploymentBadgeColorsTest {

    @Test
    void declaredHuesAreNotReducedToNeutralVariants() throws Exception {
        EnumField statuses = EnumField.builder("status")
            .value("semantic", value -> value.color(BadgeVariant.SUCCESS))
            .value("hued", value -> value.color(ColorHue.TEAL))
            .value("automatic").build();

        // 1. Both instance lanes use this row projection, preserving every shared coloring facet.
        for (String key : List.of("semantic", "hued", "automatic", "missing")) {
            WidgetBadge.Colors colors = WidgetBadge.colorsOf(statuses, key);
            InstanceDeploys.Status row = InstanceDeploymentsPage.statusOf(statuses, key);
            assertEquals(colors.variant(), row.variant(), "step 1: semantic role for " + key);
            assertEquals(colors.colorSet(), row.colorSet(), "step 1: categorical hue for " + key);
            assertEquals(colors.known(), row.known(), "step 1: declared-key fact for " + key);
        }

        // 2. The stack's fixed model field cannot substitute this fixture; pin its shared classifier and facet
        // forwarding.
        String stack =
                Files.readString(
                        Path.of("src/server/java/be/elevenways/hohenheim/server/cms/StackDeploymentsPage.java"));
        assertTrue(stack.contains("WidgetBadge.colorsOf(StackDeploymentModel.STATUS"),
                "step 2: stack uses the same home");
        assertTrue(stack.contains("entry.put(\"statusColorSet\", colors.colorSet())"),
                "step 2: stack forwards the hue");
        assertFalse(stack.contains("private static BadgeVariant statusVariant"),
                "step 2: no private classification copy");

        // 3. Both actual template sites carry the hue and leave an undeclared key as plain text.
        String instance = Files.readString(Path.of("src/common/templates/cms/hh-instance-deploys.hwk"));
        assertTrue(instance.contains("colorSet={% deploy.status.colorSet %}"), "step 3: hue on the instance part");
        assertTrue(instance.contains("{% elseif deploy.status.known %}"), "step 3: unknown-key honesty on the instance");
        String stackTemplate = Files.readString(Path.of("src/common/templates/cms/stack-deployments.hwk"));
        assertTrue(stackTemplate.contains("colorSet={% deploy.get(\"statusColorSet\") %}"), "step 3: hue on the stack");
        assertTrue(stackTemplate.contains("deploy.get(\"statusKnown\") == true"), "step 3: unknown-key honesty");
    }
}
