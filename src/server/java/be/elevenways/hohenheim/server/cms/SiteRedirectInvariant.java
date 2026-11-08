package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.RedirectStatuses;
import be.elevenways.hohenheim.model.SiteRedirectModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.proxy.SiteRedirect;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * THE site-redirect invariant on the model write pipeline, for every writer: a canonical path, a target the proxy may
 * send a visitor to, no redirect onto itself, and one row per (site, path).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SiteRedirectInvariant {

    private static volatile boolean installed;

    private SiteRedirectInvariant() {
    }

    /** Installs the invariant once per JVM. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        SiteRedirectModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null) {
                check(row);
            }
        });
    }

    private static void check(@NonNull Row row) {
        Model model = Models.get(SiteRedirectModel.class);
        Row stored = StoredRows.of(model, row);
        Object siteIdValue = row.afterWrite(SiteRedirectModel.SITE_ID, stored);
        if (!(siteIdValue instanceof Integer siteId)) {
            throw Violations.ofField(SiteRedirectModel.SITE_ID.getName(), siteIdValue,
                HohenheimMicrocopy.VIOLATIONS.of("site_required"));
        }
        Object raw = row.afterWrite(SiteRedirectModel.FROM_PATH, stored);
        String path = SiteRedirect.canonicalPath(raw);
        if (path == null) {
            throw Violations.ofField(SiteRedirectModel.FROM_PATH.getName(), raw,
                HohenheimMicrocopy.VIOLATIONS.of("redirect_path_invalid"));
        }
        if (row.isWritten(SiteRedirectModel.FROM_PATH) && !path.equals(raw)) {
            row.set(SiteRedirectModel.FROM_PATH, path);
        }
        Object targetValue = row.afterWrite(SiteRedirectModel.TARGET, stored);
        String target = targetValue == null ? null : String.valueOf(targetValue).strip();
        if (!RedirectStatuses.isTarget(target)) {
            throw Violations.ofField(SiteRedirectModel.TARGET.getName(), targetValue,
                HohenheimMicrocopy.VIOLATIONS.of("redirect_target_invalid"));
        }
        if (row.isWritten(SiteRedirectModel.TARGET) && !target.equals(targetValue)) {
            row.set(SiteRedirectModel.TARGET, target);
        }
        boolean prefix = SiteRedirectModel.MATCH_PREFIX.equals(row.afterWrite(SiteRedirectModel.MATCH, stored));
        SiteRedirect redirect = new SiteRedirect(path, prefix, target, 0);
        Object ownId = stored != null ? stored.get(SiteRedirectModel.ID) : null;
        List<SiteRedirect> others = new ArrayList<>();
        for (Row candidate : model.find().where(SiteRedirectModel.SITE_ID.eq(siteId)).all()) {
            if (Objects.equals(candidate.get(SiteRedirectModel.ID), ownId)) {
                continue;
            }
            String otherPath = SiteRedirect.canonicalPath(candidate.get(SiteRedirectModel.FROM_PATH));
            if (path.equals(otherPath)) {
                throw Violations.ofField(SiteRedirectModel.FROM_PATH.getName(), path,
                    HohenheimMicrocopy.VIOLATIONS.of("redirect_path_taken"));
            }
            Object otherTarget = candidate.get(SiteRedirectModel.TARGET);
            if (otherPath != null && otherTarget != null) {
                others.add(new SiteRedirect(otherPath,
                    SiteRedirectModel.MATCH_PREFIX.equals(candidate.get(SiteRedirectModel.MATCH)),
                    String.valueOf(otherTarget), 0));
            }
        }
        // Followed as the proxy would answer it, through any number of the site's other redirects.
        if (SiteRedirect.loopsBack(redirect, others)) {
            throw Violations.ofField(SiteRedirectModel.TARGET.getName(), target,
                HohenheimMicrocopy.VIOLATIONS.of("redirect_loops"));
        }
    }
}
