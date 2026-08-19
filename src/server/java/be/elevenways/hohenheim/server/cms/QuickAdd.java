package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.cms.common.render.CmsQuickAddFunctions;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RecordCreateProvider;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.forms.common.choose.InlineCreateFieldState;
import be.elevenways.zenit.forms.common.choose.InlineCreateFormState;
import be.elevenways.zenit.forms.common.choose.InlineCreatePresets;
import be.elevenways.zenit.forms.common.choose.ZfChooserClient;
import be.elevenways.zenit.forms.common.render.FormOptionState;
import be.elevenways.zenit.forms.server.choose.InlineCreateStates;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code cms-quick-add} element's server-side state for a BESPOKE page: the create-field
 * states of the resource's declared quick-create entries, a seeded value map, and the presets
 * the page itself supplies.
 *
 * AIDEV-NOTE: the generated list page gets this from zenit-cms (QuickAddState), which is
 * package-private there -- the element is host-agnostic by design but its state builder is not
 * yet reachable, so a host page rendering the bar composes it from the same public parts
 * (record source, create provider, InlineCreateStates, ZfChooserClient). Promoting the
 * framework's builder is a follow-up; every GATE below is an affordance gate anyway -- the
 * inline-create endpoints re-check all of them per request.
 *
 * @author Jelle De Loecker
 */
final class QuickAdd {

    private QuickAdd() {}

    /**
     * Fill the quick-add vars, leaving the bar disabled whenever anything it needs is absent
     * or refused.
     *
     * @param refreshUrl the page URL (with its state) an add returns to
     * @param createUrl  the full create form, for the sub-schema divert
     * @param presets    values for the entries the resource declared preset
     */
    static void putVars(@NonNull Map<String, Object> vars,
                        @NonNull Resource<?> resource,
                        @NonNull AccessContext accessContext,
                        @NonNull String refreshUrl,
                        @Nullable String createUrl,
                        @NonNull Map<String, Object> presets) {
        vars.put("quickAddEnabled", false);
        vars.put("quickAddForm", null);
        vars.put("quickAddValues", new LinkedHashMap<String, Object>());
        vars.put("quickAddPresets", Map.of());
        vars.put("quickAddRefreshUrl", refreshUrl);
        vars.put("quickAddCreateUrl", createUrl == null ? "" : createUrl);

        if (resource.quickCreate() == null || createUrl == null) {
            return;
        }
        Model model = resource.model();
        RecordSource<?> source = model == null ? null
            : RecordSourceRegistry.INSTANCE.byId(model.getModelId());
        if (source == null || !source.isCreatable()) {
            return;
        }
        RecordCreateProvider provider = source.createProvider();
        if (provider == null) {
            return;
        }
        Permission createPermission = source.createPermission();
        if (createPermission != null && !accessContext.hasPermission(createPermission)) {
            return;
        }
        if (!provider.authorizes(accessContext)) {
            return;
        }

        List<String> presetNames = provider instanceof InlineCreatePresets declared
            ? declared.presetNames() : List.of();
        if (!presets.keySet().containsAll(presetNames)) {
            // An unanswered preset would fail every add with a violation on a field
            // nothing renders; no bar is the honest answer.
            return;
        }

        InlineCreateFormState form = InlineCreateStates.translate(
            source, provider.createSpec(), EditContext.of(accessContext), presetNames);

        // Seeded SERVER-side, so the first paint already carries the inputs.
        Map<String, Object> values = new LinkedHashMap<>();
        ZfChooserClient.seedCreateValues(values, form.fields());
        applyStickyPicks(values, form, accessContext);

        vars.put("quickAddEnabled", true);
        vars.put("quickAddForm", form);
        vars.put("quickAddValues", values);
        vars.put("quickAddPresets", presets);
    }

    /**
     * Re-seed the select entries the bar kept across its post-add refresh. Only a value the
     * field itself DECLARES is honored: the picks travel by URL, so an unknown one is a stale
     * or hand-edited link, and seeding it would put a value in the form nothing can render.
     */
    private static void applyStickyPicks(@NonNull Map<String, Object> values,
                                         @NonNull InlineCreateFormState form,
                                         @NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        if (conduit == null) {
            return;
        }
        for (InlineCreateFieldState field : form.fields()) {
            if (field.options().isEmpty()) {
                continue;
            }
            String picked = conduit.getQueryParam(
                CmsQuickAddFunctions.STICKY_PARAM_PREFIX + field.name());
            if (picked == null || picked.isEmpty()) {
                continue;
            }
            for (FormOptionState option : field.options()) {
                if (option.inputValue().equals(picked)) {
                    values.put(field.name(), picked);
                    break;
                }
            }
        }
    }
}
