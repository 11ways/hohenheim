package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.common.data.RecordCreateProvider;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.forms.common.choose.InlineCreateForms;
import be.elevenways.zenit.forms.common.choose.InlineCreatePresets;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The inline-create half of the DNS record source: what the quick-add bar renders
 * and how it persists.
 *
 * AIDEV-NOTE: zenit-cms derives exactly this for every RowResource whose source it
 * registers itself, but DnsRecordModel declares no display fields, so the framework
 * derives NO source for it and hohenheim registers one explicitly (ManagePanel, for
 * the tenant read scope). An explicit source replaces the derived default whole, so
 * the create half has to be declared here too or the bar simply never appears.
 * Nothing is re-decided here: the reduction reads the resource's own
 * {@code quickCreate()} declaration and persistence is its own {@code persistRow},
 * so the codec validation and the zone serial bump fire exactly as on the full form.
 */
final class DnsQuickCreateProvider implements RecordCreateProvider, InlineCreatePresets {

    /**
     * A DnsRecordResource is a declaration holder (its specs are built in its
     * constructor) and its write methods read no instance state, so this private
     * instance runs the same pipeline the panel's peer does.
     */
    private final DnsRecordResource resource = new DnsRecordResource();

    private final @NonNull FormSpec spec;
    private final @NonNull List<String> presetNames;

    DnsQuickCreateProvider() {
        QuickCreateSpec quickCreate = Objects.requireNonNull(this.resource.quickCreate(),
            "DnsRecordResource must declare a quickCreate() for the bar to exist");
        this.presetNames = quickCreate.presetNames();
        this.spec = InlineCreateForms.reduceSpec(
            this.resource.formSpec().forView(EditView.CREATE),
            quickCreate.entryNames(), new LinkedHashSet<>(this.presetNames));
    }

    @Override
    public @NonNull FormSpec createSpec() {
        return this.spec;
    }

    @Override
    public @NonNull List<String> presetNames() {
        return this.presetNames;
    }

    @Override
    public @NonNull Object create(@NonNull Map<String, Object> coercedValues,
                                  @NonNull AccessContext accessContext) {
        return this.resource.persistRow(coercedValues, accessContext);
    }
}
