package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.source.GitPickerFormEntries;
import be.elevenways.hohenheim.server.database.DatabaseContainerKind;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.SystemContainerKind;
import be.elevenways.hohenheim.server.instance.VmKind;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.hohenheim.server.stack.StackServiceKind;
import be.elevenways.hohenheim.server.upstream.kinds.AddressUpstreamKind;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.forms.common.edit.FormFieldDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The settings sub-forms fold, and WHAT stays visible is pinned rather than emergent.
 *
 * AIDEV-NOTE: the visible-run assertions are the drift guard these declarations need. A
 * field added to a kind (or to GitSourceSchema, which two kinds host) lands in the
 * unsectioned leading run by default, so it becomes visible on the create form without
 * anybody deciding that. Pinning the run by name makes that decision a failing test
 * instead of a silently longer form.
 */
class InstanceSettingsSectionTest {

    /**
     * AIDEV-NOTE: no server boots in this class, so the {@code @ZenitAutoLoad} sentinels
     * that register these field-to-entry derivations must be touched by hand -- an
     * unregistered field class makes {@code deriveSpec} throw by name.
     */
    @BeforeAll
    static void loadFieldDerivations() {
        assertThat(FormFieldDefaults.LOADED).isTrue();
        assertThat(GitPickerFormEntries.LOADED).isTrue();
    }

    /** The names a schema renders OUTSIDE every section, in render order. */
    private static List<String> visibleRun(Schema schema) {
        List<String> sectioned = new ArrayList<>();
        for (FormSection section : schema.getSections()) {
            sectioned.addAll(section.entryNames());
        }
        List<String> visible = new ArrayList<>();
        for (Field<?, ?> field : schema.getFields().values()) {
            if (!sectioned.contains(field.getName())) {
                visible.add(field.getName());
            }
        }
        return visible;
    }

    private static List<String> sectionMembers(Schema schema, String id) {
        for (FormSection section : schema.getSections()) {
            if (section.id().equals(id)) {
                return section.entryNames();
            }
        }
        throw new AssertionError("schema declares no section '" + id + "'");
    }

    /**
     * The two git-sourced kinds: a short visible run, and the long tail behind named
     * folds. Step 4 is the falsification -- the sections must SURVIVE derivation, which
     * is the only reason a schema can declare them at all.
     */
    @Test
    void gitSourcedKindsLeadWithTheDecisionsAndFoldTheRest() {

        // 1. A workspace is decided by where the code is, what builds it, what starts it
        //    and which port it serves. Seven fields, out of twenty-two.
        assertThat(visibleRun(WorkspaceKind.SETTINGS_SCHEMA))
            .as("step 1: the workspace create form leads with seven fields")
            .containsExactly("repository_url", "provider_id", "repository", "branch",
                "build_command", "start_command", "container_port");

        // 2. And the other fifteen sit in three folds that say what is inside them.
        //    Three, not four: a collapsed bar costs as much height as three folded fields
        //    save, so previews ride the deployment lane they belong to.
        assertThat(WorkspaceKind.SETTINGS_SCHEMA.getSections())
            .as("step 2: three named sections, never one 'advanced' drawer")
            .extracting(FormSection::id)
            .containsExactly(HohenheimFormSections.BUILD, HohenheimFormSections.DEPLOYMENT,
                HohenheimFormSections.RUNTIME);
        assertThat(WorkspaceKind.SETTINGS_SCHEMA.getSections())
            .as("step 2: every one of them starts folded")
            .allMatch(FormSection::startsCollapsed);
        assertThat(sectionMembers(WorkspaceKind.SETTINGS_SCHEMA, HohenheimFormSections.RUNTIME))
            .as("step 2: the runtime fold carries the environment, every ceiling and the"
                + " console shape")
            .containsExactly("home_quota_mb", "environment_variables", "memory_limit_mb",
                "cpu_limit", "console_kind");
        assertThat(sectionMembers(WorkspaceKind.SETTINGS_SCHEMA, HohenheimFormSections.DEPLOYMENT))
            .as("step 2: and the deployment fold owns the preview lane too")
            .containsExactly("auto_deploy", "poll_interval", "webhook_secret",
                "previews_enabled", "preview_branches", "preview_environment_variables");

        // 3. An application answers the same question with its own extra vocabulary: the
        //    builder detail joins the shared git build fold, health and retention join
        //    delivery.
        assertThat(visibleRun(ApplicationKind.SETTINGS_SCHEMA))
            .as("step 3: an application leads with source, artifact and port")
            .containsExactly("repository_url", "provider_id", "repository", "branch",
                "build_command", "image", "tag", "container_port");
        assertThat(sectionMembers(ApplicationKind.SETTINGS_SCHEMA, HohenheimFormSections.BUILD))
            .as("step 3: its build fold owns both halves, its own first")
            .containsExactly("builder", "dockerfile", "build_arguments", "build_directory",
                "build_timeout", "build_environment_variables", "shallow_clone", "submodules");
        assertThat(sectionMembers(ApplicationKind.SETTINGS_SCHEMA, HohenheimFormSections.DEPLOYMENT))
            .as("step 3: and its deployment fold carries the release gate and the previews")
            .containsExactly("auto_deploy", "poll_interval", "webhook_secret",
                "health_path", "keep_releases", "previews_enabled", "preview_branches",
                "preview_environment_variables");

        // 4. FALSIFICATION: a schema-declared section that does not survive derivation
        //    folds nothing, because the rendered form is built from the DERIVED spec.
        FormSpec derived = FieldFormEntryRegistry.INSTANCE.deriveSpec(WorkspaceKind.SETTINGS_SCHEMA);
        assertThat(derived.sections()).extracting(FormSection::id)
            .as("step 4: the derived spec carries every declared section")
            .containsExactly(HohenheimFormSections.BUILD, HohenheimFormSections.DEPLOYMENT,
                HohenheimFormSections.RUNTIME);
        assertThat(derived.sectionOf("memory_limit_mb"))
            .as("step 4: and each member resolves to its section")
            .isNotNull()
            .extracting(FormSection::id).isEqualTo(HohenheimFormSections.RUNTIME);
        assertThat(derived.sectionOf("repository_url"))
            .as("step 4: while a visible field belongs to none").isNull();
    }

    /** The other kinds fold what has a working default and keep what a person decides. */
    @Test
    void everyOtherSettingsFormKeepsItsDecisionsVisible() {

        // 1. A raw container: the image, the command and what it carries stay; HOW the
        //    port is published and what it may consume fold.
        assertThat(visibleRun(DockerContainerKind.SETTINGS_SCHEMA))
            .containsExactly("image", "tag", "command", "container_port",
                "environment_variables", "volumes");
        assertThat(sectionMembers(DockerContainerKind.SETTINGS_SCHEMA, HohenheimFormSections.PORTS))
            .containsExactly("port_protocol", "port_exposure", "host_port");
        assertThat(sectionMembers(DockerContainerKind.SETTINGS_SCHEMA, HohenheimFormSections.RUNTIME))
            .as("the console shape defaults to plain, so it folds")
            .containsExactly("console_kind");

        // 2. A system container adds the privilege declaration to that visible run --
        //    a security decision is never folded away.
        assertThat(visibleRun(SystemContainerKind.SETTINGS_SCHEMA))
            .containsExactly("image", "image_origin", "environment_variables", "privileged");

        // 3. A VM is SIZED when it is created, so the ceilings stay visible there and
        //    only the guest half folds -- the same fields, the opposite call, because
        //    the question the kind asks is different.
        assertThat(visibleRun(VmKind.SETTINGS_SCHEMA))
            .containsExactly("image", "memory_limit_mb", "cpu_limit", "root_disk_gb",
                "network_limit_mbit", "image_origin");
        assertThat(sectionMembers(VmKind.SETTINGS_SCHEMA, HohenheimFormSections.GUEST))
            .containsExactly("cloud_init", "secure_boot", "guest_agent");

        // 4. The generated kinds keep the machine-written handles in the form (a fold is
        //    never a payload filter) but out of the way.
        assertThat(visibleRun(DatabaseContainerKind.SETTINGS_SCHEMA))
            .containsExactly("engine", "image", "ephemeral");
        assertThat(sectionMembers(ReleaseKind.SETTINGS_SCHEMA, HohenheimFormSections.MANAGED))
            .containsExactly("built_image_id", "image_ref", "source_fingerprint");
        assertThat(sectionMembers(StackServiceKind.SETTINGS_SCHEMA, HohenheimFormSections.MANAGED))
            .containsExactly("stack_network", "service_name");
        assertThat(visibleRun(StackServiceKind.SETTINGS_SCHEMA))
            .containsExactly("image", "command", "environment_variables", "volumes", "ports");

        // 5. And the upstream that carries ten knobs leads with the four that name the
        //    destination.
        assertThat(visibleRun(AddressUpstreamKind.SETTINGS_SCHEMA))
            .containsExactly("forward_scheme", "forward_host", "forward_port", "socket");
    }

    /**
     * The declaration refusals, falsified one by one: each of the three ways to get a
     * section wrong throws where it is WRITTEN, not at the form that would have rendered
     * it -- which is the whole reason these declarations are safe to edit.
     */
    @Test
    void aWrongSectionDeclarationIsRefusedWhereItIsWritten() {
        assertThatThrownBy(() -> schemaWith("alpha", "beta")
                .addSection(FormSection.of("s", "s", "gamma")))
            .as("step 1: a member the schema does not declare")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("gamma")
            .hasMessageContaining("schema");

        assertThatThrownBy(() -> schemaWith("alpha", "beta")
                .addSection(FormSection.of("s", "s", "alpha"))
                .addSection(FormSection.of("t", "t", "alpha")))
            .as("step 2: a member two sections both claim")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alpha");

        assertThatThrownBy(() -> schemaWith("alpha", "beta")
                .addSection(FormSection.of("s", "s", "alpha"))
                .addSection(FormSection.of("s", "s", "beta")))
            .as("step 3: a duplicate section id")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate section id");

        // 4. The control: the same declaration spelled correctly is accepted.
        Schema good = schemaWith("alpha", "beta");
        good.addSection(FormSection.of("s", "s", "beta"));
        assertThat(good.getSections()).as("step 4: the control declaration stands").hasSize(1);
    }

    private static Schema schemaWith(String... names) {
        Schema schema = new Schema();
        for (String name : names) {
            schema.addField(StringField.builder().name(name).build());
        }
        return schema;
    }
}
