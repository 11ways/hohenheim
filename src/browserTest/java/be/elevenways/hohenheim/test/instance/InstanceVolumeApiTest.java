package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static be.elevenways.hohenheim.test.ApiSupport.codeOf;
import static be.elevenways.hohenheim.test.ApiSupport.form;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An app's volumes over the API are the Volumes tab's own operations: declared, redeclared and deleted with data by
 * an admin key, read by a key that may see the app, refused to everyone else, and never an existence oracle.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class InstanceVolumeApiTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "volume-api-";

    private static int appId;
    private static int otherAppId;
    private static int siteId;
    private static String adminKey;
    private static String viewerKey;
    private static String siteManagerKey;

    @BeforeAll
    static void seed() {
        appId = app(PREFIX + "app");
        otherAppId = app(PREFIX + "other");
        // The site that serves the app: managing it is no authority over the app it exposes.
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, PREFIX + "site");
        site.set(SiteModel.SLUG, PREFIX + "site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
        site.set(SiteModel.INSTANCE_ID, appId);
        site.set(SiteModel.ENABLED, false);
        Models.get(SiteModel.class).save(site);
        siteId = site.get(SiteModel.ID);

        UUID viewer = ApiSupport.user(PREFIX + "viewer@surface.test", "Volume Viewer");
        RecordGrants.grant(GrantSubjectType.USER, viewer, InstanceModel.MODEL_ID, appId,
            HohenheimCapabilities.VIEW, true);
        viewerKey = ApiKeyService.create(viewer, PREFIX + "viewer",
            List.of(CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimCapabilities.VIEW)), null).plaintext();
        UUID siteManager = ApiSupport.user(PREFIX + "site-manager@surface.test", "Volume Site Manager");
        RecordGrants.grant(GrantSubjectType.USER, siteManager, SiteModel.MODEL_ID, siteId,
            HohenheimCapabilities.MANAGE, true);
        siteManagerKey = ApiKeyService.create(siteManager, PREFIX + "site-manager",
            List.of(CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimCapabilities.MANAGE)), null).plaintext();
        UUID adminId = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        adminKey = ApiKeyService.create(adminId, PREFIX + "admin", List.of("hohenheim.*"), null).plaintext();
    }

    @AfterAll
    static void cleanUp() {
        Models.get(InstanceVolumeModel.class).find()
            .where(InstanceVolumeModel.INSTANCE_ID.in(appId, otherAppId)).delete();
        HardDeletes.byId(Models.get(SiteModel.class), siteId);
        HardDeletes.byId(Models.get(InstanceModel.class), appId);
        HardDeletes.byId(Models.get(InstanceModel.class), otherAppId);
    }

    /** Journey: the doors, then a volume declared (its directory with it), redeclared and deleted with its data. */
    @Test
    void anAppsVolumesAreTheVolumesTabsOperations() throws Exception {
        String volumes = "/api/v1/instances/" + appId + "/volumes";
        FakeHostVolumes hosts = FakeHostVolumes.install();
        try {
            journey(volumes, hosts);
        } finally {
            FakeHostVolumes.uninstall();
        }
    }

    private void journey(String volumes, FakeHostVolumes hosts) throws Exception {

        // 1. Nobody who may not see the app learns it exists: a site manager without the app, an unknown app and
        //    a caller with no key are refused, and nothing is declared.
        assertThat(keyGet(siteManagerKey, volumes).statusCode()).as("step 1: the site manager reads nothing")
            .isEqualTo(404);
        assertThat(keyPost(siteManagerKey, volumes, declaration("data", "/data")).statusCode())
            .as("step 1: nor declares").isEqualTo(404);
        assertThat(keyGet(adminKey, "/api/v1/instances/0/volumes").statusCode())
            .as("step 1: an unknown app is the same 404").isEqualTo(404);
        assertThat(sendRequest(requestTo(volumes).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(declaration("data", "/data")))).statusCode())
            .as("step 1: a caller without a key is refused").isEqualTo(401);

        // 2. A key that may see the app reads its volumes, as the /manage twin shows them, and the operation's own
        //    gate refuses it the declaration.
        HttpResponse<String> viewed = keyGet(viewerKey, volumes);
        assertThat(viewed.statusCode()).as("step 2: the viewer reads the list").isEqualTo(200);
        assertThat(viewed.body()).as("step 2: an empty one").contains("\"volumes\":[]");
        assertThat(keyPost(viewerKey, volumes, declaration("data", "/data")).statusCode())
            .as("step 2: but is refused the declaration").isEqualTo(403);
        assertThat(volume(appId, "data")).as("step 2: and nothing was declared").isNull();

        // 3. The admin key declares it; the route's app is fixed, so a body naming another app is refused, and the
        //    declaration's own refusals answer by name.
        HttpResponse<String> declared = keyPost(adminKey, volumes, declaration("data", "/data") + "&quota_mb=512");
        assertThat(declared.statusCode()).as("step 3: declared (%s)", declared.body()).isEqualTo(200);
        assertThat(declared.body()).as("step 3: answering the volume").contains("\"status\":\"declared\"")
            .contains("\"container_path\":\"\\/data\"").contains("\"quota_mb\":512");
        assertThat((Object) volume(appId, "data").get(InstanceVolumeModel.QUOTA_BYTES))
            .as("step 3: stored as the panel stores it").isEqualTo(VolumeOperations.quotaBytes(512));
        assertThat(keyGet(viewerKey, volumes).body()).as("step 3: the viewer now reads it").contains("\"data\"");
        assertThat(hosts.directoryOf(HostModel.nameOf(HostModel.localHostId()),
            InstanceVolumes.hostPathFor(appId, "data"))).as("step 3: the declaration created its directory")
            .isDirectory();
        HttpResponse<String> elsewhere = keyPost(adminKey, volumes, declaration("stray", "/stray")
            + "&instance_id=" + otherAppId);
        assertThat(elsewhere.statusCode()).as("step 3: another app is refused (%s)", elsewhere.body())
            .isEqualTo(422);
        assertThat(volume(otherAppId, "stray")).as("step 3: and gets no volume").isNull();
        assertThat(volume(appId, "stray")).as("step 3: nor does this app").isNull();
        HttpResponse<String> badName = keyPost(adminKey, volumes, declaration("../etc", "/etc"));
        assertThat(badName.statusCode()).as("step 3: a path for a name is refused").isEqualTo(422);
        assertThat(codeOf(badName.body())).as("step 3: by name").isEqualTo("volume_name_invalid");

        // 4. Redeclared: only what the body names changes; the viewer is refused for a known and an unknown name
        //    alike (the refusal on the app comes before the lookup), and an unknown name is the 404 only to a caller
        //    who may redeclare.
        HttpResponse<String> redeclared = keyPost(adminKey, volumes + "/update", form("name", "data",
            "quota_mb", "1024"));
        assertThat(redeclared.statusCode()).as("step 4: redeclared (%s)", redeclared.body()).isEqualTo(200);
        assertThat(redeclared.body()).as("step 4: the new limit").contains("\"quota_mb\":1024");
        assertThat((String) volume(appId, "data").get(InstanceVolumeModel.CONTAINER_PATH))
            .as("step 4: the path it left out is kept").isEqualTo("/data");
        for (String name : List.of("data", "nope")) {
            assertThat(keyPost(viewerKey, volumes + "/update", form("name", name, "quota_mb", "1")).statusCode())
                .as("step 4: the viewer is refused for '%s'", name).isEqualTo(403);
        }
        assertThat(keyPost(adminKey, volumes + "/update", form("name", "nope", "quota_mb", "1")).statusCode())
            .as("step 4: an unknown volume is 404").isEqualTo(404);
        assertThat((Object) volume(appId, "data").get(InstanceVolumeModel.QUOTA_BYTES))
            .as("step 4: the refused redeclarations changed nothing").isEqualTo(VolumeOperations.quotaBytes(1024));

        // 5. Deleted with its data: refused to the viewer (a known and an unknown name alike), done for the admin,
        //    and gone afterwards.
        for (String name : List.of("data", "nope")) {
            assertThat(keyPost(viewerKey, volumes + "/delete", form("name", name)).statusCode())
                .as("step 5: the viewer is refused for '%s'", name).isEqualTo(403);
        }
        assertThat(volume(appId, "data")).as("step 5: and the volume stays").isNotNull();
        HttpResponse<String> deleted = keyPost(adminKey, volumes + "/delete", form("name", "data"));
        assertThat(deleted.statusCode()).as("step 5: deleted (%s)", deleted.body()).isEqualTo(200);
        assertThat(deleted.body()).as("step 5: naming it").contains("\"status\":\"deleted\"")
            .contains("\"name\":\"data\"");
        assertThat(volume(appId, "data")).as("step 5: the declaration is gone").isNull();
        assertThat(keyPost(adminKey, volumes + "/delete", form("name", "data")).statusCode())
            .as("step 5: a second delete is the 404").isEqualTo(404);
    }

    private static String declaration(String name, String containerPath) {
        return form("name", name, "container_path", containerPath);
    }

    private static Row volume(int instanceId, String name) {
        return Models.get(InstanceVolumeModel.class).find()
            .where(InstanceVolumeModel.INSTANCE_ID.eq(instanceId))
            .where(InstanceVolumeModel.NAME.eq(name)).first();
    }

    private static int app(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
