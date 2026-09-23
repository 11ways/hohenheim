package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.plumage.component.Pager;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.common.render.table.TableState;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The zone's Records tab is the record resource's own PAGED list scoped to one zone, not a
 * second unpaged query with its own search.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class DnsZoneRecordsTabTest extends HohenheimTestBase {

    /** One full page plus a remainder, so the tab needs a second page. */
    private static final int RECORDS = TableSpec.DEFAULT_PAGE_SIZE + 7;

    private static int zoneId;
    private static int otherZoneId;

    @BeforeAll
    static void seedZones() {
        zoneId = zone("paged-records.example");
        otherZoneId = zone("other-records.example");
        for (int i = 0; i < RECORDS; i++) {
            record(zoneId, String.format("rec-%03d", i), "192.0.2." + (i % 250));
        }
        // Named like a record of the paged zone, so only the SCOPE can keep it out.
        record(otherZoneId, "rec-000", "198.51.100.1");
    }

    @Test
    void theRecordsTabPagesTheZoneThroughTheResource() {
        AccessContext operator = operator();

        // 1. The first page holds exactly one page of THIS zone's records, and the pager
        //    knows the whole zone's total.
        Map<String, Object> first = render(operator, Map.of());
        TableState firstTable = (TableState) first.get("table");
        assertThat(firstTable.rows())
            .as("step 1: the first page is one page long, not the whole zone")
            .hasSize(TableSpec.DEFAULT_PAGE_SIZE);
        Pager pager = (Pager) first.get("pager");
        assertThat(pager.total()).as("step 1: the pager counts every record of the zone").isEqualTo(RECORDS);
        assertThat(pager.pageCount()).as("step 1: the remainder lands on a second page").isEqualTo(2);
        assertThat(pager.next()).as("step 1: the next rung stays on the zone's own tab")
            .contains("/records").contains("page=2");

        // 2. The second page holds the remainder, and no row repeats across the two pages.
        Map<String, Object> second = render(operator, Map.of("page", "2"));
        TableState secondTable = (TableState) second.get("table");
        assertThat(secondTable.rows()).as("step 2: page two holds the remainder").hasSize(7);
        Set<String> keys = new HashSet<>();
        firstTable.rows().forEach(row -> keys.add(row.key()));
        secondTable.rows().forEach(row -> assertThat(keys.add(row.key()))
            .as("step 2: record %s appears on one page only", row.key()).isTrue());

        // 3. The zone scope holds on every page: the other zone's record is never listed.
        int foreign = recordId(otherZoneId, "rec-000");
        assertThat(keys).as("step 3: another zone's record never reaches this tab")
            .doesNotContain(String.valueOf(foreign));

        // 4. The search is the resource's own, still inside the zone: the other zone's
        //    rec-000 matches the term too, and is still not listed.
        Map<String, Object> searched = render(operator, Map.of("search", "rec-00"));
        TableState searchedTable = (TableState) searched.get("table");
        assertThat(searchedTable.rows())
            .as("step 4: the search narrows within the zone (rec-000 .. rec-009)")
            .hasSize(10);
        assertThat(((Pager) searched.get("pager")).next())
            .as("step 4: one page of results needs no next rung")
            .isEmpty();

        // 5. A malformed page parameter degrades to the first page, never a 500.
        Map<String, Object> malformed = render(operator, Map.of("page", "two"));
        assertThat(((TableState) malformed.get("table")).rows())
            .as("step 5: a malformed page reads as the first page")
            .hasSize(TableSpec.DEFAULT_PAGE_SIZE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> render(AccessContext operator, Map<String, String> query) {
        Conduit conduit = withQuery(operator.conduit(), query);
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        return (Map<String, Object>) new DnsZoneRecordsPage()
            .renderLocal(conduit, AccessContext.of(conduit), zone, new DnsRecordResource()).get();
    }

    /** The stub carrier, additionally answering the given query string parameters. */
    private static Conduit withQuery(Conduit base, Map<String, String> query) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getQueryParam".equals(method.getName()) && args != null && args.length == 1) {
                return query.get(String.valueOf(args[0]));
            }
            if ("getConduit".equals(method.getName())) {
                return proxy;
            }
            try {
                return method.invoke(base, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        };
        return (Conduit) Proxy.newProxyInstance(DnsZoneRecordsTabTest.class.getClassLoader(),
            new Class<?>[] { Conduit.class }, handler);
    }

    private static AccessContext operator() {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));
    }

    private static int zone(String origin) {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, origin);
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1." + origin);
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@" + origin);
        zone.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_PRIMARY);
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);
        return zone.get(DnsZoneModel.ID);
    }

    private static int recordId(int zone, String name) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zone))
            .where(DnsRecordModel.NAME.eq(name))
            .first().get(DnsRecordModel.ID);
    }

    private static void record(int zone, String name, String value) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row record = records.createEmptyRow();
        record.set(DnsRecordModel.ZONE_ID, zone);
        record.set(DnsRecordModel.NAME, name);
        record.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
        record.set(DnsRecordModel.VALUE, value);
        record.set(DnsRecordModel.TTL, 300);
        record.set(DnsRecordModel.ENABLED, true);
        records.save(record);
    }
}
