package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.common.edit.FieldAccess;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared shape of an operation HISTORY an orchestrator writes (builds, releases): the generated
 * resource IS the page, nav-hidden, never created here, and every entry read-only.
 *
 * AIDEV-NOTE: the rows are written by their engine only (the build orchestrator, the release
 * engine), so every field is read-only through the FieldAccess binding (the ActivityResource
 * shape) -- an editable image pin or step log would be a second authority over what ran and what
 * served when. A subclass declares only what differs: its columns, its form, its slug, its model.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
abstract class OperationHistoryResource extends RowResource {

    private final @NonNull String scope;

    /**
     * @param scope the resource's microcopy scope, which is also its identifier path
     */
    OperationHistoryResource(@NonNull String scope) {
        this.scope = scope;
    }

    @Override public final @NonNull Identifier id() { return Identifier.of("hohenheim", this.scope); }
    @Override public final @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", this.scope); }
    @Override public final @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", this.scope); }
    @Override public final @NonNull ListChrome listChrome() { return CmsSupport.WIDE_LIST; }
    @Override public final @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }

    /**
     * Demoted out of the sidebar, so this sentence reaches a reader through the panel
     * index and the related-pages menu of the list that names it.
     */
    @Override public final @Nullable Microcopy description() { return CmsSupport.navHint(this.scope); }

    @Override public final boolean showInNav() { return false; }
    @Override public final boolean creatable() { return false; }

    /** Every field read-only: the engine owns these rows. */
    @Override
    public final @NonNull List<ResourceFieldBinding> fieldBindings() {
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (var entry : this.formSpec().entries()) {
            bindings.add(ResourceFieldBinding.of(entry.name(), FieldAccess.alwaysReadonly()));
        }
        return bindings;
    }
}
