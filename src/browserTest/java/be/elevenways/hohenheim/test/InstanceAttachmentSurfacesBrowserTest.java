package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceAttachmentOperations;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.InstanceAttachmentParts;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.render.table.SynthesizedRowActions;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The instance-database attachment entry and its /manage twin, stored before their move onto shared parts and
 * compared exactly after it. The device twins' set lives beside the other /manage captures
 * ({@code ManagePanelSurfacesBrowserTest}).
 *
 * AIDEV-NOTE: a failing comparison is a changed surface, never a file to refresh. The refused admin case is named
 * after the tenant it was first captured with; that tenant holds nothing on the attachment.
 */
class InstanceAttachmentSurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "b14-attachments-";
    private static final String ADMIN = HohenheimSlugs.ADMIN;
    private static final String MANAGE = HohenheimSlugs.MANAGE;
    private static final String DATABASES = InstanceAttachmentParts.DATABASES;

    private static String instanceId;
    private static String databaseId;
    private static String linkId;
    private static AccessContext operator;
    private static AccessContext outsider;
    private static AccessContext tenantDatabases;

    @BeforeAll
    static void seed() {
        int outsiderUser = ApiSupport.user(PREFIX + "outsider@hohenheim.local", "B14 Attachments Outsider");
        int databasesUser = ApiSupport.user(PREFIX + "databases@hohenheim.local", "B14 Databases Tenant");

        int instance = instance(PREFIX + "instance");
        instanceId = String.valueOf(instance);
        int database = database(PREFIX + "db");
        databaseId = String.valueOf(database);
        linkId = String.valueOf(link(instance, database));

        RecordGrants.grant(GrantSubjectType.USER, databasesUser, InstanceModel.MODEL_ID, instance,
            HohenheimAccess.CONFIG, true);
        RecordGrants.grant(GrantSubjectType.USER, databasesUser, DatabaseModel.MODEL_ID, database,
            HohenheimAccess.MANAGE, true);

        operator = access(operatorPrincipal());
        outsider = access(new UserPrincipal(outsiderUser, "B14 Attachments Outsider"));
        tenantDatabases = access(new UserPrincipal(databasesUser, "B14 Databases Tenant"));
    }

    @Test
    void theAttachmentEntryOffersWhatItOfferedBeforeTheMove() {
        // The delete moved onto the attachment's own delete operation.
        SurfaceBaselines stored = SurfaceBaselines.load(InstanceAttachmentSurfacesBrowserTest.class,
                "/panel-surfaces/instance-attachments/" + DATABASES + ".txt")
            .placedOperations(PlacedOperationMoves.NONE.synthesized(DATABASES, SynthesizedRowActions.DELETE,
                InstanceAttachmentOperations.DELETE_DATABASE_LINK.id()));

        // 1. The admin entry for the operator, record-less, on its record and with the instance prefill; a tenant is
        //    refused the admin panel.
        stored.check(capture(SurfaceCase.of(ADMIN, DATABASES, "operator", operator)));
        stored.check(capture(SurfaceCase.of(ADMIN, DATABASES, "tenant-snapshots", outsider)
            .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        stored.check(capture(SurfaceCase.of(ADMIN, DATABASES, "operator", operator).onRecord(linkId, "link")));
        stored.check(capture(SurfaceCase.of(ADMIN, DATABASES, "operator", operator)
            .named(ADMIN + "." + DATABASES + ".operator.prefill")
            .withParameter(HohenheimParams.INSTANCE_ID_PREFILL.getName(), instanceId)));

        // 2. The /manage twin for the tenant holding both sides of the attachment.
        stored.check(capture(SurfaceCase.of(MANAGE, DATABASES, "tenant-databases", tenantDatabases)));
        stored.check(capture(SurfaceCase.of(MANAGE, DATABASES, "tenant-databases", tenantDatabases)
            .onRecord(linkId, "link")));

        // 3. Every stored case matched exactly.
        stored.finish();
    }

    /** A capture with every generated fixture id declared at the bindings a destination carries it. */
    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture
            .key(HohenheimSlugs.INSTANCES, "instance", instanceId)
            .key(HohenheimParams.INSTANCE_ID_PREFILL.getName(), "instance", instanceId)
            .key("databases", "database", databaseId)
            .key(DATABASES, "link", linkId));
    }

    private static AccessContext access(UserPrincipal principal) {
        return AccessContext.of(TenantConduits.stubFor(principal));
    }

    private static UserPrincipal operatorPrincipal() {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    private static int instance(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int database(String name) {
        Model databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.IMAGE, "postgres:17-alpine");
        row.set(DatabaseModel.DB_USER, "artifactuser");
        row.set(DatabaseModel.DB_PASSWORD, "artifactpass");
        row.set(DatabaseModel.DB_NAME, "artifactdb");
        row.set(DatabaseModel.EPHEMERAL, false);
        databases.save(row);
        return row.get(DatabaseModel.ID);
    }

    private static int link(int instance, int database) {
        Model links = Models.get(InstanceDatabaseModel.class);
        Row row = links.createEmptyRow();
        row.set(InstanceDatabaseModel.INSTANCE_ID, instance);
        row.set(InstanceDatabaseModel.DATABASE_ID, database);
        row.set(InstanceDatabaseModel.ENV_PREFIX, "APPDB");
        links.save(row);
        return row.get(InstanceDatabaseModel.ID);
    }
}
