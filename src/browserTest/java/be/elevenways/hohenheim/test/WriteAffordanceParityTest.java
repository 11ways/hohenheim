package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.DatabaseResource;
import be.elevenways.hohenheim.server.cms.DnsRecordResource;
import be.elevenways.hohenheim.server.cms.InstanceDatabaseResource;
import be.elevenways.hohenheim.server.cms.InstanceResource;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Affordance-versus-funnel parity on every resource whose READ scope is wider than its
 * WRITE authority: the synthesized Edit/Delete affordances (and the detail form's Save
 * behind them) are offered exactly where the write pipeline would accept -- the
 * {@code InstanceDeviceResource} lesson, applied to the four remaining surfaces.
 *
 * Enforcement was never broken (TenantWrites refuses every one of these on the model
 * pipeline); what these pin is that the SURFACE now agrees with the funnel instead of
 * showing a view-only delegate buttons that can only refuse. Each block carries its
 * positive anchor, so a surface that refuses everyone cannot pass.
 */
class WriteAffordanceParityTest extends HohenheimTestBase {

    private static final String PREFIX = "affparity-";

    private static Integer viewerId;
    private static Integer holderId;

    private static Integer instanceId;
    private static Integer databaseId;
    private static Integer linkId;
    private static Integer zoneId;
    private static Integer recordId;
    private static Integer foreignTypeRecordId;

    @BeforeAll
    static void seed() {
        viewerId = user("affparity-viewer@surface.test", "Affordance Viewer");
        holderId = user("affparity-holder@surface.test", "Affordance Holder");

        Model instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, PREFIX + "instance");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(instance);
        instanceId = instance.get(InstanceModel.ID);

        Model databases = Models.get(DatabaseModel.class);
        Row database = databases.createEmptyRow();
        database.set(DatabaseModel.NAME, PREFIX + "db");
        database.set(DatabaseModel.ENGINE, "postgres");
        database.set(DatabaseModel.DB_NAME, PREFIX + "db");
        database.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(database);
        databaseId = database.get(DatabaseModel.ID);

        Model links = Models.get(InstanceDatabaseModel.class);
        Row link = links.createEmptyRow();
        link.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        link.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
        link.set(InstanceDatabaseModel.ENV_PREFIX, InstanceDatabaseModel.DEFAULT_PREFIX);
        links.save(link);
        linkId = link.get(InstanceDatabaseModel.ID);

        Model zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, "affparity.test");
        zone.set(DnsZoneModel.ENABLED, true);
        zone.set(DnsZoneModel.DEFAULT_TTL, 3600);
        zone.set(DnsZoneModel.NEGATIVE_TTL, 300);
        zone.set(DnsZoneModel.SOA_REFRESH, 7200);
        zone.set(DnsZoneModel.SOA_RETRY, 3600);
        zone.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        zones.save(zone);
        zoneId = zone.get(DnsZoneModel.ID);

        recordId = dnsRecord("editable", DnsRecordModel.TYPE_A, "192.0.2.10");
        foreignTypeRecordId = dnsRecord("delegated", DnsRecordModel.TYPE_NS, "ns1.example.org");

        // The viewer holds the READ half everywhere it exists as a verb.
        RecordGrants.grant("user", viewerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.VIEW, true);
        RecordGrants.grant("user", viewerId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimAccess.VIEW, true);
        RecordGrants.grant("user", viewerId, DnsRecordModel.MODEL_ID, recordId,
            HohenheimAccess.VIEW, true);

        // The holder carries exactly what each funnel demands.
        RecordGrants.grant("user", holderId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.CONFIG, true);
        RecordGrants.grant("user", holderId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", holderId, DnsRecordModel.MODEL_ID, recordId,
            HohenheimAccess.EDIT, true);
        RecordGrants.grant("user", holderId, DnsRecordModel.MODEL_ID, foreignTypeRecordId,
            HohenheimAccess.EDIT, true);
    }

    private static int dnsRecord(String name, String type, String value) {
        Model records = Models.get(DnsRecordModel.class);
        Row row = records.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zoneId);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, type);
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.TTL, 300);
        row.set(DnsRecordModel.ENABLED, true);
        records.save(row);
        return row.get(DnsRecordModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        if (linkId != null) {
            Models.get(InstanceDatabaseModel.class).delete(linkId);
        }
        if (recordId != null) {
            Models.get(DnsRecordModel.class).delete(recordId);
        }
        if (foreignTypeRecordId != null) {
            Models.get(DnsRecordModel.class).delete(foreignTypeRecordId);
        }
        if (zoneId != null) {
            Models.get(DnsZoneModel.class).delete(zoneId);
        }
        if (databaseId != null) {
            Models.get(DatabaseModel.class).delete(databaseId);
        }
        if (instanceId != null) {
            Models.get(InstanceModel.class).delete(instanceId);
        }
    }

    private static int user(String email, String name) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, email);
        row.set(UserModel.DISPLAY_NAME, name);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Instant.now());
        row.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(row);
        return row.get(UserModel.ID);
    }

    private static AccessContext viewer() {
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(viewerId, "Affordance Viewer")));
    }

    private static AccessContext holder() {
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(holderId, "Affordance Holder")));
    }

    private static AccessContext operator() {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));
    }

    /** An instance UPDATE is a CONFIG act (TenantWrites.checkInstanceWrite). */
    @Test
    void theInstanceEditorFollowsConfig() {
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        InstanceResource resource = new InstanceResource();

        assertThat(resource.updatableBy(instance, viewer()))
            .as("a view-only delegate is offered no instance editor").isFalse();
        assertThat(resource.updatableBy(instance, holder()))
            .as("a config holder keeps it").isTrue();
        assertThat(resource.updatableBy(instance, operator()))
            .as("and the operator passes through the walk's admin row").isTrue();
    }

    /** A database DELETE is a DESTROY act (the model's before-remove hook). */
    @Test
    void theDatabaseDeleteFollowsDestroy() {
        Row database = Models.get(DatabaseModel.class).findById(databaseId);
        DatabaseResource resource = new DatabaseResource();

        assertThat(resource.deletableBy(database, viewer()))
            .as("a view-only delegate is offered no destroy button").isFalse();
        // MANAGE implies DESTROY on databases, so the holder passes the implied row.
        assertThat(resource.deletableBy(database, holder()))
            .as("a manage holder keeps its destroy button").isTrue();
        assertThat(resource.deletableBy(database, operator()))
            .as("and the operator passes").isTrue();
    }

    /** A link write is two-sided: instance CONFIG plus database MANAGE, both or neither. */
    @Test
    void theAttachmentAffordancesFollowBothSides() {
        Row link = Models.get(InstanceDatabaseModel.class).findById(linkId);
        InstanceDatabaseResource resource = new InstanceDatabaseResource();

        assertThat(resource.updatableBy(link, viewer()))
            .as("a view-only delegate is offered no attachment editor").isFalse();
        assertThat(resource.deletableBy(link, viewer()))
            .as("nor a detach button").isFalse();
        assertThat(resource.updatableBy(link, holder()))
            .as("the two-sided holder keeps its editor").isTrue();
        assertThat(resource.deletableBy(link, holder()))
            .as("and its detach button").isTrue();

        // ONE-SIDED: revoke the database half and the affordance must fall with it --
        // the exact laundering the two-sided funnel rule exists to refuse. revoke,
        // never grant(false): a planted deny is sticky and would outlive the finally.
        RecordGrants.revoke("user", holderId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimAccess.MANAGE);
        try {
            assertThat(resource.updatableBy(link, holder()))
                .as("instance config alone does not earn the attachment editor").isFalse();
            assertThat(resource.deletableBy(link, holder()))
                .as("nor the detach button").isFalse();
        } finally {
            RecordGrants.grant("user", holderId, DatabaseModel.MODEL_ID, databaseId,
                HohenheimAccess.MANAGE, true);
        }
    }

    /**
     * A DNS record write is the union of per-record {@code edit} and hostname authority,
     * inside the tenant-authorable TYPE allow-list -- and the affordance mirrors all
     * three clauses, foreign types included.
     */
    @Test
    void theDnsRecordAffordancesFollowTheRecordLanes() {
        Row editable = Models.get(DnsRecordModel.class).findById(recordId);
        Row delegated = Models.get(DnsRecordModel.class).findById(foreignTypeRecordId);
        DnsRecordResource resource = new DnsRecordResource();

        assertThat(resource.updatableBy(editable, viewer()))
            .as("a view-only delegate is offered no record editor").isFalse();
        assertThat(resource.deletableBy(editable, viewer()))
            .as("nor a delete button").isFalse();
        assertThat(resource.updatableBy(editable, holder()))
            .as("an edit-grant holder keeps its editor").isTrue();
        assertThat(resource.deletableBy(editable, holder()))
            .as("and its delete button").isTrue();

        // The TYPE clause: an NS row is a zone-compromise primitive the pipeline refuses
        // for EVERY tenant writer, edit grant or not -- so no affordance either.
        assertThat(resource.updatableBy(delegated, holder()))
            .as("an NS row offers no tenant editor even to an edit-grant holder")
            .isFalse();
        assertThat(resource.deletableBy(delegated, holder()))
            .as("nor a delete button").isFalse();

        // While the operator, whom the tenant lanes never gate, keeps both on both rows.
        assertThat(resource.updatableBy(delegated, operator()))
            .as("the operator keeps the NS editor").isTrue();
        assertThat(resource.deletableBy(editable, operator()))
            .as("and every delete button").isTrue();
    }
}
