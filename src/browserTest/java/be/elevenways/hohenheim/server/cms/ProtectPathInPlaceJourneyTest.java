package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Protecting a path in place: one operation on the site writes a dedicated list, its rules and the protected path,
 * and an answer that names nobody is refused before anything is written.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class ProtectPathInPlaceJourneyTest extends HohenheimTestBase {

    @Test
    void aPathIsProtectedInPlaceByPasswordOrNetworkAndNeverOpen() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Row site = site("protect-" + suffix, "hohenheim:static");

        // 1. With a password: the people entry (an item list inside the operation's input) is coerced, each person
        //    becomes a basic-credentials rule with a HASHED password, on a dedicated unshared list, and the path it
        //    protects is not open.
        Map<String, Object> password = form("/wp-admin", ProtectPath.METHOD_PASSWORD);
        password.put(ProtectPath.PEOPLE.name(), List.of(
            Map.of("username", "anna", "password", "first-secret"),
            Map.of("username", "ben", "password", "second-secret")));
        Row protectedByPassword = protect(site, password);
        Row list = Models.get(AccessListModel.class).findById(protectedByPassword.get(ProtectedPathModel.ACCESS_LIST_ID));
        assertThat((Boolean) list.get(AccessListModel.SHARED)).as("step 1: the list is the path's own").isFalse();
        List<Row> rules = Models.get(AccessRuleModel.class).findForAccessList(list.get(AccessListModel.ID));
        assertThat(rules).as("step 1: one rule per person").hasSize(2);
        for (Row rule : rules) {
            assertThat((String) rule.get(AccessRuleModel.TYPE)).as("step 1: a password rule")
                .isEqualTo(AccessRuleModel.TYPE_BASIC_AUTH);
            Object stored = AccessRuleModel.dataOf(rule).get(AccessRuleModel.BASIC_AUTH_PASSWORD.getName());
            assertThat(BasicCredentials.isHashed((String) stored)).as("step 1: the password is stored hashed").isTrue();
        }
        assertThat(ProtectedPathInvariant.isOpen(protectedByPassword)).as("step 1: the path is not open").isFalse();

        // 2. From a network: one allow rule per network.
        Map<String, Object> network = form("/internal", ProtectPath.METHOD_NETWORK);
        network.put(ProtectPath.NETWORKS.getName(), List.of("203.0.113.0/24"));
        Row protectedByNetwork = protect(site, network);
        List<Row> networkRules = Models.get(AccessRuleModel.class).findForAccessList(protectedByNetwork.get(ProtectedPathModel.ACCESS_LIST_ID));
        assertThat(networkRules).as("step 2: one rule for the one network").hasSize(1);
        assertThat((String) networkRules.getFirst().get(AccessRuleModel.TYPE)).as("step 2: an allow rule")
            .isEqualTo(AccessRuleModel.TYPE_IP_ALLOW);

        // 3. An answer that names nobody is refused for every method, and nothing is written.
        long pathsBefore = paths(site);
        long listsBefore = Models.get(AccessListModel.class).find().count();
        for (String method : List.of(ProtectPath.METHOD_PASSWORD, ProtectPath.METHOD_NETWORK,
                ProtectPath.METHOD_SIGN_IN)) {
            Throwable refused = catchThrowable(() -> protect(site, form("/empty-" + method, method)));
            assertThat(refused).as("step 3: an empty " + method + " answer is refused").isNotNull();
        }
        assertThat(paths(site)).as("step 3: no path was written").isEqualTo(pathsBefore);
        assertThat(Models.get(AccessListModel.class).find().count()).as("step 3: and no list either")
            .isEqualTo(listsBefore);

        // 4. A TLS passthrough site never sees a path, so it is not offered the operation at all.
        Row passthrough = site("protect-pass-" + suffix, "hohenheim:tls_passthrough");
        assertThat(OperationPipeline.offer(ProtectPath.OPERATION, TenantConduits.operator(), passthrough))
            .as("step 4: a passthrough site is not offered protection")
            .isInstanceOf(OperationPipeline.Offer.Hidden.class);
    }

    private static Map<String, Object> form(String path, String method) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(ProtectPath.PATH.getName(), path);
        form.put(ProtectPath.METHOD.getName(), method);
        return form;
    }

    private static Row protect(Row site, Map<String, Object> form) {
        OperationPipeline.invoke(OperationRequest.of(ProtectPath.OPERATION, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator()).subjects(List.of(site)).form(form));
        return Models.get(ProtectedPathModel.class).find()
            .where(ProtectedPathModel.SITE_ID.eq(site.get(SiteModel.ID)))
            .where(ProtectedPathModel.PATH.eq((String) form.get(ProtectPath.PATH.getName())))
            .first();
    }

    private static long paths(Row site) {
        return Models.get(ProtectedPathModel.class).find()
            .where(ProtectedPathModel.SITE_ID.eq(site.get(SiteModel.ID))).count();
    }

    private static Row site(String name, String kind) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, kind);
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, false);
        sites.save(site);
        return site;
    }
}
