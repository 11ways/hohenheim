package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SecurityReporterModel;
import be.elevenways.hohenheim.server.security.SecurityReporters;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.flash.FlashToast;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.server.page.CmsPageContext;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Security-event reporters: create mints the hashed zsec_ ingest token
 * server-side and the RAW token rides the one-shot flash toast; rotate does
 * the same for an existing reporter (the HubProjectResource shape).
 * Site-linked reporters are auto-minted by env injection and show up here too.
 */
public final class SecurityReporterResource extends RowResource {

    private static final Identifier ROTATE = Identifier.of("hohenheim", "rotate_reporter_token");

    private final FormSpec formSpec = FormSpec.builder()
        .add(SecurityReporterModel.NAME)
        .add(SecurityReporterModel.ENABLED)
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "security_reporter"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "security_reporter"); }
    @Override public @NonNull String slug() { return "security-reporters"; }
    @Override public @NonNull Model model() { return Models.get(SecurityReporterModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 30; }
    @Override public @NonNull Icon icon() { return Icon.of("tower-broadcast"); }

    @Override
    public @NonNull TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .columnFromField(SecurityReporterModel.NAME)
            .columnFromField(SecurityReporterModel.SITE_ID)
            .columnFromField(SecurityReporterModel.ENABLED)
            .columnFromField(SecurityReporterModel.LAST_SEEN_AT)
            .defaultSort(SortSpec.asc("name"))
            .build();
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        String name = coerced.get("name") instanceof String text && !text.isBlank()
            ? text.trim() : "reporter";
        boolean enabled = !Boolean.FALSE.equals(coerced.get("enabled"));

        SecurityReporters.MintedToken minted = SecurityReporters.create(name, null, enabled);

        CmsPageContext.stashFlashToast(accessContext.conduit(),
            new FlashToast(Microcopy.of("token_created")
                    .withFilter("scope", "security_reporter")
                    .withArg("token", minted.raw()),
                CmsActionResult.Toast.Level.SUCCESS));

        return minted.reporterId();
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        return List.of(RowAction.Invoke.<Row>builder(ROTATE)
            .label(Microcopy.of("rotate_token").withFilter("scope", "security_reporter"))
            .description(Microcopy.of("rotate_token_hint").withFilter("scope", "security_reporter"))
            .icon(Icon.of("rotate"))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("rotate_token").withFilter("scope", "security_reporter"))
                .body(Microcopy.of("rotate_token_confirm").withFilter("scope", "security_reporter"))
                .build())
            .handler((row, ctx) -> {
                Integer id = row.get(SecurityReporterModel.ID);
                if (id == null) {
                    return CmsActionResult.errorToast(
                        Microcopy.of("save_failed").withFilter("scope", "cms"));
                }
                SecurityReporters.MintedToken minted = SecurityReporters.rotate(id);
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("token_rotated")
                        .withFilter("scope", "security_reporter")
                        .withArg("token", minted.raw()));
            })
            .build());
    }
}
