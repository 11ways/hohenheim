package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.PasswordHasher;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * IP allow/deny lists plus optional single-credential basic auth, attachable to sites.
 */
public final class AccessListResource extends RowResource {

    private static final List<FieldOption<String>> SATISFY_OPTIONS = List.of(
        FieldOption.of(AccessListModel.SATISFY_ANY,
            Microcopy.of("any").withFilter("scope", "access_satisfy")),
        FieldOption.of("all",
            Microcopy.of("all").withFilter("scope", "access_satisfy")));

    private final FormSpec formSpec = FormSpec.builder()
        .add(AccessListModel.NAME)
        .add(Select.of(AccessListModel.SATISFY).options(OptionSource.of(SATISFY_OPTIONS)).build())
        .add(AccessListModel.BASIC_AUTH_USER)
        .add(AccessListModel.BASIC_AUTH_PASS)
        .add(AccessListModel.ALLOWED_IPS)
        .add(AccessListModel.DENIED_IPS)
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "access_list"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "access_list"); }
    @Override public @NonNull String slug() { return "access-lists"; }
    @Override public @NonNull Model model() { return Models.get(AccessListModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 40; }
    @Override public @NonNull Icon icon() { return Icon.of("shield-halved"); }

    @Override
    public void applyValuesToRow(@NonNull Row row, @NonNull Map<String, Object> coerced) {
        super.applyValuesToRow(row, coerced);
        String password = row.get(AccessListModel.BASIC_AUTH_PASS);
        if (password != null && !password.isBlank() && !password.startsWith("$argon2")) {
            row.set(AccessListModel.BASIC_AUTH_PASS, PasswordHasher.hash(password));
        }
    }

}
