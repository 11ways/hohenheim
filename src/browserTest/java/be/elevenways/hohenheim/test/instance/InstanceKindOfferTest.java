package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.InstanceResource;
import be.elevenways.hohenheim.server.cms.InstanceTemplateResource;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.edit.submit.SubmittedValueCoercion;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Kind picker offers exactly the kinds a human may author, and the write guard that
 * refuses the rest reads the SAME declaration.
 *
 * AIDEV-NOTE: the offer is asserted by DERIVATION (a live registry sweep), never against a
 * pinned list -- a seventh kind must answer for itself with no test edit, the
 * NotificationAdminTest stance rather than the TaskBootstrap PINNED-list one. Before this,
 * the create form offered "Site container", "Stack service" and "Database container",
 * every one of which the OwnedInstances hook could only refuse.
 */
class InstanceKindOfferTest {

    @BeforeAll
    static void bootRegistry() {
        HohenheimTestRuntime.ensureDatasource();
    }

    private static List<String> optionValues(FormEntry entry) {
        assertThat(entry).as("the kind entry is a Select").isInstanceOf(Select.class);
        List<FieldOption<?>> resolved =
            ((Select) entry).options().resolve(EditContext.of(AccessContext.anonymous()));
        List<String> values = new ArrayList<>();
        for (FieldOption<?> option : resolved) {
            values.add(String.valueOf(option.value()));
        }
        return values;
    }

    /** Every registered id whose handler declares generatedOnly(), read live. */
    private static List<String> generatedOnlyIds() {
        List<String> ids = new ArrayList<>();
        for (InstanceKindInfo entry : InstanceKindRegistry.REGISTRY) {
            Identifier id = InstanceKindRegistry.REGISTRY.getId(entry);
            if (id == null) {
                continue;
            }
            InstanceKindHandler handler = InstanceKinds.getHandler(id.toString());
            if (handler != null && handler.generatedOnly()) {
                ids.add(id.toString());
            }
        }
        return ids;
    }

    private static List<String> registeredIds() {
        List<String> ids = new ArrayList<>();
        for (InstanceKindInfo entry : InstanceKindRegistry.REGISTRY) {
            Identifier id = InstanceKindRegistry.REGISTRY.getId(entry);
            if (id != null) {
                ids.add(id.toString());
            }
        }
        return ids;
    }

    @Test
    @DisplayName("the offer is the registry minus what the write guard refuses, derived not listed")
    void theOfferIsDerivedFromTheSameDeclarationAsTheRefusal() {

        List<String> registered = registeredIds();
        List<String> generatedOnly = generatedOnlyIds();

        // 1. The fixture is only meaningful if BOTH sides are non-empty: without a
        //    generated-only kind this test would pass against a picker that filters nothing.
        assertThat(generatedOnly)
            .as("step 1: some kind must be generated-only or this test proves nothing")
            .isNotEmpty();
        assertThat(registered)
            .as("step 1: and some kind must be authorable, or the picker is empty")
            .hasSizeGreaterThan(generatedOnly.size());

        // 2. THE INVARIANT: offered == registered - generatedOnly. Set equality, so a
        //    seventh kind is covered the day it lands.
        List<String> expected = new ArrayList<>(registered);
        expected.removeAll(generatedOnly);

        assertThat(InstanceKinds.authorableOptions().stream().map(FieldOption::value).toList())
            .as("step 2: the offer is exactly the authorable set")
            .containsExactlyInAnyOrderElementsOf(expected);

        // 3. The instance create form consumes that derivation -- the positive anchor
        //    (a real kind is offered) beside the negative one, so this can distinguish
        //    "filters correctly" from "offers nothing at all".
        List<String> offered = optionValues(new InstanceResource().formSpec().findEntry("kind"));
        assertThat(offered)
            .as("step 3: the create form offers every authorable kind")
            .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(offered)
            .as("step 3: and none the write guard would refuse")
            .doesNotContainAnyElementsOf(generatedOnly);

        // 4. A template of a generated-only kind is the same lie one level removed: every
        //    create from it lands on the same refusal.
        assertThat(optionValues(new InstanceTemplateResource().formSpec().findEntry("kind")))
            .as("step 4: the template form is narrowed the same way")
            .doesNotContainAnyElementsOf(generatedOnly);
    }

    @Test
    @DisplayName("a hand-posted generated-only kind is refused by the form layer, not only the write guard")
    void aForgedKindIsRefusedBeforeTheWriteGuard() {

        String refused = generatedOnlyIds().get(0);

        // 1. Hiding the option is not a gate: coercion re-checks the offer, so a POST that
        //    never went through the form is refused on the "kind" field.
        assertThatThrownBy(() -> SubmittedValueCoercion.coerceFormOrThrow(
                new InstanceResource().formSpec().forView(EditView.CREATE),
                Map.of("name", "forged-kind", "kind", refused)))
            .as("step 1: the form layer refuses a kind it does not offer")
            .isInstanceOf(Violations.class);

        // 2. And the write guard stays the authoritative second half -- it is reached by
        //    every writer that never sees a form (the peer API, a direct model.save).
        assertThatThrownBy(() -> InstanceKinds.requireAuthorable(refused))
            .as("step 2: the model-pipeline refusal is unchanged")
            .isInstanceOf(Violations.class);

        // 3. An authorable kind passes both, so step 1 and 2 are not refusing everything.
        InstanceKinds.requireAuthorable(InstanceKinds.authorableOptions().get(0).value());
    }

    @Test
    @DisplayName("every kind's supported runtimes are real host runtimes, and the set is never empty")
    void supportedRuntimesBindToTheHostRuntimeVocabulary() {

        // 1. The host-runtime vocabulary is declared ONCE, on ServerModel.RUNTIME. A kind
        //    naming a runtime no host can declare places nowhere and says nothing about it.
        Set<String> hostRuntimes = ServerModel.RUNTIME.getValues().keySet();
        assertThat(hostRuntimes)
            .as("step 1: hosts declare a populated runtime vocabulary")
            .isNotEmpty();

        boolean someKindRunsOnBoth = false;

        for (String id : registeredIds()) {
            InstanceKindHandler handler = InstanceKinds.getHandler(id);
            Set<String> supported = handler.supportedRuntimes();

            // 2. Never empty: an empty set is a kind whose placement chooser can only
            //    return "nothing accepts this workload", with no reason an operator can act on.
            assertThat(supported)
                .as("step 2: '%s' declares at least one runtime", id)
                .isNotEmpty();

            // 3. And every member is a runtime a host can actually be.
            assertThat(hostRuntimes)
                .as("step 3: '%s' names only declared host runtimes", id)
                .containsAll(supported);

            someKindRunsOnBoth |= supported.size() > 1;
        }

        // 4. The set shape earns its keep: the workspace kind is the reason it stopped
        //    being one string, so a regression back to a single runtime fails here.
        assertThat(someKindRunsOnBoth)
            .as("step 4: at least one kind runs on more than one runtime")
            .isTrue();
        assertThat(InstanceKinds.getHandler("hohenheim:workspace").supportedRuntimes())
            .as("step 4: and it is the workspace, on both runtimes")
            .containsExactlyInAnyOrder(ServerModel.RUNTIME_DOCKER, ServerModel.RUNTIME_INCUS);
    }

    @Test
    @DisplayName("narrowing the offer did not narrow the labels")
    void existingRowsOfARefusedKindStillRenderTheirKind() {

        String refused = generatedOnlyIds().get(0);

        // Records of these kinds EXIST (a site's release container is one) and are listed
        // wherever their owning tier shows them. Label rendering reads the field's own value
        // map, which still enumerates the whole registry -- if this ever fails, the fix
        // narrowed the wrong layer and every generated row now renders an unknown kind.
        assertThat(InstanceModel.KIND.getValues())
            .as("the field still knows every registered kind, offered or not")
            .containsKey(refused);
    }
}
