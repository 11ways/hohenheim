package be.elevenways.hohenheim.source;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.forms.common.edit.ProviderPick;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the git source picker entries: {@link GitProviderRefField} becomes a
 * select over the registered provider rows, {@link GitRepositoryField} and
 * {@link GitBranchField} become sibling-driven provider picks (repository follows
 * the chosen provider, branch follows provider AND repository). freeText stays on
 * both pickers deliberately -- a listing caps at the provider API's first page,
 * and a path beyond it must remain enterable.
 */
@ZenitAutoLoad
public final class GitPickerFormEntries {

    /** Auto-load sentinel; reading it runs the registrations below. */
    public static final boolean LOADED = register();

    private GitPickerFormEntries() {
    }

    private static boolean register() {
        FieldFormEntryRegistry registry = FieldFormEntryRegistry.INSTANCE;

        registry.register(GitProviderRefField.class, field -> Select.of(field)
            .options(OptionSource.supplied(GitPickerFormEntries::providerOptions))
            .build());

        registry.register(GitRepositoryField.class, field -> ProviderPick.of(field)
            .providerFromSiblings(new GitRepositoryResolver(), "provider_id")
            .freeText()
            .build());

        registry.register(GitBranchField.class, field -> ProviderPick.of(field)
            .providerFromSiblings(new GitBranchResolver(), "provider_id", "repository")
            .freeText()
            .build());

        return true;
    }

    /** The registered provider rows as options; resolved per render, server-side. */
    private static @NonNull List<FieldOption<Integer>> providerOptions() {
        List<FieldOption<Integer>> options = new ArrayList<>();
        var model = Models.get(GitProviderModel.class);
        for (Row provider : model.find().orderBy(GitProviderModel.NAME, SortOrder.ASC).all()) {
            String name = provider.get(GitProviderModel.NAME);
            FieldOption<Integer> option = FieldOption.of(
                provider.get(GitProviderModel.ID),
                Microcopy.literal(name != null ? name : ""));
            EnumField.EnumValue kind = GitProviderModel.KIND.getValues()
                .get(provider.get(GitProviderModel.KIND));
            Icon icon = kind != null ? kind.getIcon() : null;
            options.add(icon != null ? option.withIcon(icon.name()) : option);
        }
        return options;
    }
}
