package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.server.cms.DnsRecordResource;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DNS record admin surface must follow the record's TYPE: the dyndns token
 * action exists only where dynamic DNS applies (address records), and the same
 * predicate is the invoke-time enforcement (hidden action = 404, never a toast).
 */
class DnsRecordTypeSurfaceTest extends HohenheimTestBase {

    /** Journey: seed a TXT and an A record, then walk the action surface both ways. */
    @Test
    void dyndnsActionFollowsTheRecordType() throws Exception {
        // 1. Seed one zone carrying a TXT record and an A record.
        int zoneId = DnsFixtures.createZone("type-surface.example");

        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row txt = records.createEmptyRow();
        txt.set(DnsRecordModel.ZONE_ID, zoneId);
        txt.set(DnsRecordModel.NAME, "canary");
        txt.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_TXT);
        txt.set(DnsRecordModel.VALUE, "not-an-address");
        txt.set(DnsRecordModel.ENABLED, true);
        records.save(txt);

        Row a = records.createEmptyRow();
        a.set(DnsRecordModel.ZONE_ID, zoneId);
        a.set(DnsRecordModel.NAME, "home");
        a.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
        a.set(DnsRecordModel.VALUE, "192.0.2.40");
        a.set(DnsRecordModel.ENABLED, true);
        records.save(a);

        // 2. The mint action's own visibility predicate is the surface: it is what the
        //    list renders from AND what invoke re-checks (ResourcePageEndpoints 404s a
        //    hidden action). An operator context passes the capability walk, so type is
        //    the deciding axis.
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        AccessContext operator = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(((Integer) admin.get(UserModel.ID)).longValue(),
                "Test Admin")));

        RowAction.Invoke<Row> mint = null;
        RowAction.Invoke<Row> revoke = null;
        for (RowAction<Row> action : new DnsRecordResource().rowActions()) {
            if ("dyndns_token".equals(action.id().getPath())
                    && action instanceof RowAction.Invoke<Row> invoke) {
                mint = invoke;
            }
            if ("dyndns_revoke".equals(action.id().getPath())
                    && action instanceof RowAction.Invoke<Row> invoke) {
                revoke = invoke;
            }
        }
        assertThat(mint).as("2. the dyndns mint action exists on the resource").isNotNull();
        assertThat(mint.isVisibleFor(a, operator))
            .as("2. an A record offers the dyndns token action").isTrue();
        assertThat(mint.isVisibleFor(txt, operator))
            .as("2. a TXT record must NOT offer the dyndns token action").isFalse();
        assertThat(revoke).as("2. the dyndns revoke action exists on the resource").isNotNull();
        assertThat(revoke.isVisibleFor(a, operator))
            .as("2. an UNARMED A record does not offer revoke (nothing to revoke)").isFalse();
        assertThat(revoke.isVisibleFor(txt, operator))
            .as("2. a TXT record never offers revoke").isFalse();

        // 3. Invoke-time enforcement is the same predicate: a direct POST against the
        //    TXT record answers 404, never a minted credential or an error toast.
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> refused = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort()
                + "/admin/dns-records/" + txt.get(DnsRecordModel.ID)
                + "/action/dyndns_token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode())
            .as("3. minting a dyndns token on a TXT record is not-found, not an error toast")
            .isEqualTo(404);

        // 4. The A record's invoke still works end to end and stays type-scoped.
        HttpResponse<String> minted = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort()
                + "/admin/dns-records/" + a.get(DnsRecordModel.ID)
                + "/action/dyndns_token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(minted.statusCode())
            .as("4. minting on an A record still succeeds (redirect back to the list)")
            .isIn(302, 303);

        // 5. An ARMED record now offers revoke too -- the action follows the credential.
        assertThat(revoke.isVisibleFor(a, operator))
            .as("5. the armed A record offers the revoke action").isTrue();
    }
}
