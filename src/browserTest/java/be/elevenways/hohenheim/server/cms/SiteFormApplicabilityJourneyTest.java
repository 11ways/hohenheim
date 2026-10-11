package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forms offer only what applies: the site form shows its instance pick for the instance kind alone, and the instance
 * form offers a kind no admitted host can run as a dead card saying so.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class SiteFormApplicabilityJourneyTest extends HohenheimTestBase {

    @Test
    void theSiteFormShowsTheInstancePickOnlyForTheInstanceKind() {
        String instanceKind = InstanceUpstreamKind.ID.toString();

        // 1. The pick applies to the instance kind and to no other.
        assertThat(SiteWrites.ADMIN_FORM.matches(SiteModel.INSTANCE_ID.getName(),
            Map.of(SiteModel.UPSTREAM_KIND.getName(), instanceKind)))
            .as("step 1: shown for the instance kind").isTrue();
        assertThat(SiteWrites.ADMIN_FORM.matches(SiteModel.INSTANCE_ID.getName(),
            Map.of(SiteModel.UPSTREAM_KIND.getName(), "hohenheim:static"))).as("step 1: hidden for a static site")
            .isFalse();

        // 2. A site switched from the instance kind to static, with the old pick still posted, loses the link: the
        //    hidden pick is never saved, so nothing unreachable stays behind.
        Row site = instanceSite("applicability-site", application("applicability-app"));
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(SiteModel.NAME.getName(), "applicability-site");
        form.put(SiteModel.UPSTREAM_KIND.getName(), "hohenheim:static");
        form.put(SiteModel.INSTANCE_ID.getName(), site.get(SiteModel.INSTANCE_ID));
        form.put(SiteModel.SETTINGS.getName(), Map.of("root_path", "/tmp/applicability-site"));
        form.put(SiteModel.ENABLED.getName(), false);
        OperationPipeline.invoke(OperationRequest.of(SiteWrites.UPDATE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator()).subjects(List.of(site)).form(form));
        Row stored = Models.get(SiteModel.class).findById(site.get(SiteModel.ID));
        assertThat((String) stored.get(SiteModel.UPSTREAM_KIND)).as("step 2: the site is static now")
            .isEqualTo("hohenheim:static");
        assertThat((Integer) stored.get(SiteModel.INSTANCE_ID)).as("step 2: and its instance link is cleared").isNull();
    }

    @Test
    void aKindNoHostRunsSaysWhatItNeedsAndStaysChoosable() {
        String incusOnly = InstanceKinds.kindsWhere(handler -> handler.supportedRuntimes()
            .equals(Set.of(ServerModel.RUNTIME_INCUS))).getFirst();
        boolean incusInventoried = !Models.get(ServerModel.class).find()
            .where(ServerModel.RUNTIME.eq(ServerModel.RUNTIME_INCUS)).all().isEmpty();

        // 1. While no Incus host exists, an Incus-only card says it needs one (the test database is shared, so the
        //    expectation reads it rather than assuming); it stays choosable, the host pick being the gate.
        FieldOption<String> card = option(InstanceKinds.placeableOptions(), incusOnly);
        assertThat(card.disabled()).as("step 1: never a dead card").isFalse();
        assertThat(card.description() != null && "kind_needs_runtime_host".equals(card.description().key()))
            .as("step 1: says it needs an Incus host exactly while none exists").isEqualTo(!incusInventoried);

        // 2. Once an Incus host is inventoried (admitted or not), the card is the kind's own again.
        Row host = Models.get(ServerModel.class).createEmptyRow();
        host.set(ServerModel.NAME, "applicability-incus");
        host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        host.set(ServerModel.MODE, ServerModel.MODE_SSH);
        host.set(ServerModel.SSH_TARGET, "operator@applicability-incus.invalid");
        host.set(ServerModel.INCUS_URL, "https://applicability-incus.invalid:8443");
        host.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        Models.get(ServerModel.class).save(host);
        FieldOption<String> after = option(InstanceKinds.placeableOptions(), incusOnly);
        assertThat(after.description() == null || !"kind_needs_runtime_host".equals(after.description().key()))
            .as("step 2: an inventoried Incus host lifts the note").isTrue();

        // 3. A Docker kind always reads as itself: the local host runs Docker.
        String dockerKind = InstanceKinds.kindsWhere(InstanceKindHandler::supportsSiteUpstream).stream()
            .filter(kind -> InstanceKinds.getHandler(kind).supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER))
            .findFirst().orElseThrow();
        FieldOption<String> docker = option(InstanceKinds.placeableOptions(), dockerKind);
        assertThat(docker.description() == null || !docker.description().key().startsWith("kind_needs"))
            .as("step 3: a Docker kind carries no note").isTrue();
    }

    private static FieldOption<String> option(List<FieldOption<String>> options, String kind) {
        return options.stream().filter(option -> option.value().equals(kind)).findFirst().orElseThrow();
    }

    private static int application(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "fake/app", "tag", "1",
            "container_port", 8080)));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static Row instanceSite(String name, int instanceId) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
        row.set(SiteModel.ENABLED, false);
        row.set(SiteModel.INSTANCE_ID, instanceId);
        Models.get(SiteModel.class).save(row);
        return Models.get(SiteModel.class).findById(row.get(SiteModel.ID));
    }
}
