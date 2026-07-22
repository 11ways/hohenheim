package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Access tab on a site (admin panel only): who holds the manage grant, with
 * add/remove forms posting to the host endpoints in HohenheimHandlers.
 */
public final class SiteAccessPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_access"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("access").withFilter("scope", "site"); }
    @Override public @NonNull String slug() { return "access"; }
    @Override public @NonNull Icon icon() { return Icon.of("user-lock"); }

    /** Granting site access is installation administration, whatever panel hosts the resource. */
    @Override
    public @Nullable Permission requiredPermission() {
        return HohenheimPanel.ACCESS;
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row site) {
        Integer siteId = site.get(SiteModel.ID);

        List<Map<String, Object>> grants = new ArrayList<>();
        for (Row grant : RecordGrants.listForRecord(SiteModel.MODEL_ID, siteId)) {
            Map<String, Object> entry = new HashMap<>();
            String subjectType = grant.get(RecordGrantModel.SUBJECT_TYPE);
            Integer subjectId = grant.get(RecordGrantModel.SUBJECT_ID);
            entry.put("subjectType", subjectType);
            entry.put("subjectId", subjectId);
            entry.put("subject", subjectDisplay(subjectType, subjectId));
            entry.put("capability", grant.get(RecordGrantModel.CAPABILITY));
            entry.put("allowed", Boolean.TRUE.equals(grant.get(RecordGrantModel.VALUE)));
            entry.put("createdAt", String.valueOf(grant.get(RecordGrantModel.CREATED_AT)));
            grants.add(entry);
        }

        List<Map<String, Object>> users = new ArrayList<>();
        for (Row user : AuthModels.users().find()
                .where(UserModel.ENABLED.eq(true))
                .orderBy(UserModel.EMAIL, SortOrder.ASC)
                .all()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", user.get(UserModel.ID));
            entry.put("label", userLabel(user));
            users.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", site.get(SiteModel.NAME) + " - Access");
        vars.put("siteId", siteId);
        vars.put("siteName", site.get(SiteModel.NAME));
        vars.put("grants", grants);
        vars.put("users", users);
        String error = conduit.getQueryParam("error");
        vars.put("error", error != null ? error : "");
        vars.put("recordTabs", recordTabs(conduit));

        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/site-access"), vars);
    }

    private static @NonNull String subjectDisplay(@Nullable String subjectType, @Nullable Integer subjectId) {
        if (subjectId == null) {
            return "?";
        }
        if ("user".equals(subjectType)) {
            Row user = AuthModels.users().findById(subjectId);
            return user != null ? userLabel(user) : "user #" + subjectId;
        }
        if ("group".equals(subjectType)) {
            Row group = AuthModels.permissionGroups().findById(subjectId);
            if (group != null) {
                String title = group.get(PermissionGroupModel.TITLE);
                return title != null && !title.isBlank()
                    ? title : String.valueOf(group.get(PermissionGroupModel.SLUG));
            }
            return "group #" + subjectId;
        }
        return subjectType + " #" + subjectId;
    }

    private static @NonNull String userLabel(@NonNull Row user) {
        String display = user.get(UserModel.DISPLAY_NAME);
        String email = user.get(UserModel.EMAIL);
        if (display != null && !display.isBlank()) {
            return email != null && !email.isBlank() ? display + " (" + email + ")" : display;
        }
        return email != null ? email : "user #" + user.get(UserModel.ID);
    }
}
