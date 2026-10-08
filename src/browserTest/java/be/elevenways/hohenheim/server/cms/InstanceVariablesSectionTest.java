package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.render.panel.ChildListSectionState;
import be.elevenways.zenit.cms.common.render.table.AbsentCellState;
import be.elevenways.zenit.cms.common.render.table.TableState;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.test.support.EndpointConduit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The variables of an instance as the framework's child list section its Provisioning tab embeds, on both panels: the
 * instance's own values in key order, a plain value shown, a secret and an unknown kind withheld before any render
 * state, never another instance's or an environment's value, and no write, reveal or detail affordance (stage 4
 * contract 10, A-G8, O07).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class InstanceVariablesSectionTest extends HohenheimTestBase {

    private static final String PREFIX = "variables-section-";
    private static final String SECRET_PLAINTEXT = "never-rendered-secret";
    private static final String UNKNOWN_PLAINTEXT = "never-rendered-unknown";

    /** How {@link #values} reads a cell the framework drew absent: the computed value answered nothing. */
    private static final String WITHHELD = "<absent>";

    /** The seeded variables' keys by their row key, so a table row reads back as its variable's key. */
    private static final Map<String, String> KEYS = new LinkedHashMap<>();

    private static int ownInstance;
    private static int foreignInstance;
    private static UserPrincipal viewer;

    @BeforeAll
    static void seed() {
        ownInstance = instance(PREFIX + "own");
        foreignInstance = instance(PREFIX + "foreign");
        variable(ownInstance, null, "ZZ_PLAIN", InstanceVariableModel.KIND_PLAIN, "visible-config");
        variable(ownInstance, null, "AA_SECRET", InstanceVariableModel.KIND_SECRET, SECRET_PLAINTEXT);
        int unknown = variable(ownInstance, null, "MM_UNKNOWN", InstanceVariableModel.KIND_PLAIN, UNKNOWN_PLAINTEXT);
        // A kind no reader knows, written past the model's own vocabulary as a legacy row would carry it.
        Models.get(InstanceVariableModel.class).find().where(InstanceVariableModel.ID.eq(unknown))
            .assign(InstanceVariableModel.KIND, "legacy_kind").updateAll();
        variable(foreignInstance, null, "FOREIGN_KEY", InstanceVariableModel.KIND_PLAIN, "foreign-value");
        variable(null, environment(), "ENVIRONMENT_KEY", InstanceVariableModel.KIND_PLAIN, "environment-value");

        int viewerId = ApiSupport.user(PREFIX + "viewer@hohenheim.local", "Variables Viewer");
        RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, ownInstance, HohenheimAccess.VIEW,
            true);
        viewer = new UserPrincipal(viewerId, "Variables Viewer");
    }

    @Test
    void theProvisioningTabEmbedsTheInstancesOwnVariablesReadOnlyOnBothPanels() {
        // 1. The operator's Provisioning tab on /admin: one section, the instance's own three values in key order,
        //    never the other instance's or the environment's.
        ChildListSectionState admin = section(HohenheimSlugs.ADMIN, operator());
        assertThat(admin.resourceSlug()).as("step 1: the variables entry's section").isEqualTo("instance-variables");
        assertThat(keys(admin)).as("step 1: the instance's own values, KEY ascending")
            .containsExactly("AA_SECRET", "MM_UNKNOWN", "ZZ_PLAIN");

        // 2. The value cell: a plain value is shown; a secret and a kind nobody knows are drawn as an absent cell.
        assertThat(values(admin)).as("step 2: only the plain value reaches the table")
            .containsExactly(WITHHELD, WITHHELD, "visible-config");

        // 3. Neither the secret's nor the unknown kind's stored text reaches the render state anywhere: no cell, no
        //    copy value, no hidden column.
        assertThat(String.valueOf(admin.table())).as("step 3: no withheld text in the table state")
            .doesNotContain(SECRET_PLAINTEXT, UNKNOWN_PLAINTEXT);

        // 4. Read-only: no create link, no row action, no bulk action, no row link to a detail or editor.
        assertThat(admin.createUrl()).as("step 4: no create").isNull();
        assertThat(admin.hasRowActions()).as("step 4: no row actions").isFalse();
        assertThat(admin.hasBulkActions()).as("step 4: no bulk actions").isFalse();
        assertThat(admin.table().rows()).as("step 4: no row leads to a variable detail")
            .allSatisfy(row -> assertThat(row.url()).isNull());

        // 5. A VIEW delegate on /manage reads the same section, the same values and the same withholding.
        ChildListSectionState manage = section(HohenheimSlugs.MANAGE, viewer);
        assertThat(keys(manage)).as("step 5: the delegate's tab lists the same values")
            .containsExactly("AA_SECRET", "MM_UNKNOWN", "ZZ_PLAIN");
        assertThat(values(manage)).as("step 5: and withholds the same ones").containsExactly(WITHHELD, WITHHELD,
            "visible-config");
        assertThat(String.valueOf(manage.table())).as("step 5: no withheld text on /manage either")
            .doesNotContain(SECRET_PLAINTEXT, UNKNOWN_PLAINTEXT);

        // 6. The tenant scope is the instance's VIEW reach: the delegate's own variables entry never lists the
        //    foreign instance's or the environment's values.
        AccessContext delegate = AccessContext.of(conduit(HohenheimSlugs.MANAGE, viewer, null));
        assertThat(TenantScopes.INSTANCE_VARIABLES.criteria(delegate)).as("step 6: the tenant scope narrows")
            .isNotNull();
        List<String> reachable = Models.get(InstanceVariableModel.class).find()
            .where(TenantScopes.INSTANCE_VARIABLES.criteria(delegate))
            .all().stream().map(row -> String.valueOf((Object) row.get(InstanceVariableModel.KEY))).toList();
        assertThat(reachable).as("step 6: only the viewable instance's values")
            .containsExactlyInAnyOrder("AA_SECRET", "MM_UNKNOWN", "ZZ_PLAIN");
    }

    private static ChildListSectionState section(String panel, Principal principal) {
        EndpointConduit conduit = conduit(panel, principal, String.valueOf(ownInstance));
        conduit.setParameter(CmsEndpoints.SUBPAGE_PARAM, InstanceProvisioningPage.SLUG);
        ActionResult<?> result = CmsEndpoints.RECORD_SUBPAGE.handle(conduit);
        assertThat(result).as(panel + ": the Provisioning tab renders").isInstanceOf(RenderTemplateResult.class);
        @SuppressWarnings("unchecked")
        List<ChildListSectionState> sections = (List<ChildListSectionState>)
            ((RenderTemplateResult) result).get().get("sections");
        assertThat(sections).as(panel + ": one embedded section").hasSize(1);
        return sections.get(0);
    }

    private static EndpointConduit conduit(String panel, Principal principal, String instanceId) {
        EndpointConduit conduit = EndpointConduit.fullPageRequest();
        conduit.setAttribute(ConduitAttributes.PRINCIPAL, principal);
        conduit.setParameter(CmsEndpoints.PANEL_PARAM, panel);
        conduit.setParameter(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.INSTANCES);
        if (instanceId != null) {
            conduit.setParameter(CmsEndpoints.RESOURCE_ID_PARAM, instanceId);
        }
        return conduit;
    }

    private static List<String> keys(ChildListSectionState section) {
        return section.table().rows().stream().map(row -> KEYS.get(row.key())).toList();
    }

    /** A row's value cell: its text, or {@link #WITHHELD} where the framework drew an absent cell. */
    private static List<Object> values(ChildListSectionState section) {
        return section.table().rows().stream().map(TableState.RowState::cells)
            .map(cells -> cells.get(InstanceVariableParts.VALUE_COLUMN))
            .map(cell -> cell instanceof AbsentCellState ? WITHHELD : cell).toList();
    }

    private static UserPrincipal operator() {
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
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

    private static int environment() {
        Row project = Models.get(ProjectModel.class).createEmptyRow();
        project.set(ProjectModel.NAME, PREFIX + "project");
        Models.get(ProjectModel.class).save(project);
        Row environment = Models.get(EnvironmentModel.class).createEmptyRow();
        environment.set(EnvironmentModel.PROJECT_ID, project.get(ProjectModel.ID));
        environment.set(EnvironmentModel.NAME, PREFIX + "environment");
        Models.get(EnvironmentModel.class).save(environment);
        return environment.get(EnvironmentModel.ID);
    }

    private static int variable(Integer instance, Integer environment, String key, String kind, String value) {
        Model variables = Models.get(InstanceVariableModel.class);
        Row row = variables.createEmptyRow();
        row.set(InstanceVariableModel.INSTANCE_ID, instance);
        row.set(InstanceVariableModel.ENVIRONMENT_ID, environment);
        row.set(InstanceVariableModel.KEY, key);
        row.set(InstanceVariableModel.KIND, kind);
        if (InstanceVariableModel.KIND_SECRET.equals(kind)) {
            row.set(InstanceVariableModel.SECRET_VALUE, value);
        } else {
            row.set(InstanceVariableModel.PLAIN_VALUE, value);
        }
        variables.save(row);
        int id = row.get(InstanceVariableModel.ID);
        KEYS.put(String.valueOf(id), key);
        return id;
    }
}
