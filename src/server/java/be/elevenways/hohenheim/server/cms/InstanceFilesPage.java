package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.sftp.server.SftpSessionInfo;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.files.FileRoot;
import be.elevenways.hohenheim.server.files.HohenheimSftp;
import be.elevenways.hohenheim.server.files.InstanceFiles;
import be.elevenways.hohenheim.server.files.SiteFiles;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.WorkloadLane;
import be.elevenways.hohenheim.server.files.InstanceSftpRealm;
import be.elevenways.hohenheim.server.util.PosixPaths;
import be.elevenways.zenit.auth.AuthEndpoints;
import be.elevenways.zenit.auth.model.SshKeyModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AccountRows;
import be.elevenways.zenit.auth.server.SshKeys;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.sftp.server.SftpHostKeys;
import be.elevenways.zenit.sftp.server.SftpServer;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.Wording;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.MalformedQuery;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Files tab on an app (a browser over its own volumes) or on a static-files site serving its own files (a browser
 * over them), plus an inline editor for text files: one page, two placements, over the one file manager.
 *
 * The page makes NO authorization decision. It asks {@link InstanceFiles}, which gates on the root's capability, and
 * renders the write controls only when the context also holds write -- an affordance on top of a gate, never instead
 * of one: the action endpoint asks the service for write authority regardless of what this page drew.
 */
public final class InstanceFilesPage implements RecordTab.Rendered<Row> {

    /** Above this, the inline editor is not offered -- a browser is not a hex editor. */
    private static final long INLINE_EDIT_LIMIT = 512 * 1024;

    /** Which records the tab sits on; every fact that differs between them is an exhaustive switch here. */
    private enum Placement { APP, SITE }

    private final @NonNull Placement placement;

    /** The app's tab. */
    public InstanceFilesPage() {
        this(Placement.APP);
    }

    private InstanceFilesPage(@NonNull Placement placement) {
        this.placement = placement;
    }

    /** @return the tab on a static-files site serving its own files */
    public static @NonNull InstanceFilesPage onSites() {
        return new InstanceFilesPage(Placement.SITE);
    }

    @Override
    public @NonNull Identifier id() {
        return switch (this.placement) {
            case APP -> HohenheimIds.id("instance_files_browser");
            case SITE -> HohenheimIds.id("site_files_browser");
        };
    }

    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.INSTANCE.of("files"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.FILES; }
    @Override public @NonNull Icon icon() { return Icon.of("folder-tree"); }

    /** A site's tab says where its files are served. */
    @Override
    public @Nullable Microcopy lead() {
        return switch (this.placement) {
            case APP -> null;
            case SITE -> HohenheimMicrocopy.INSTANCE_FILES.of("site_lead");
        };
    }

    /**
     * The tab exists only for a principal that may actually READ this record's files.
     *
     * AIDEV-NOTE: it had NO gate at all -- the tab rendered for every viewer and the
     * refusal arrived as an error banner from {@code InstanceFiles.list}, which is a
     * broken-looking tab where an absent one is the truth. Hide AND enforce, exactly like
     * the exec tab: zenit-cms 404s an unoffered slug, so this gates the route too, and
     * the service still asks the same capability on every call.
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext accessContext) {
        return switch (this.placement) {
            // AIDEV-NOTE: the gate is the DRIVER's file lane (WorkloadLane.FILES), not a volume declaration: the lane
            // browses the runtime's volumes -- Docker named volumes included -- so a docker_container legitimately has
            // files to browse, while a workload whose driver has no lane (Incus) gets no tab, here and on /manage.
            case APP -> InstanceKinds.offers(record, WorkloadLane.FILES) && HohenheimAccess.hasInstanceCapability(
                accessContext, record.get(InstanceModel.ID), HohenheimCapabilities.FILES_READ);
            case SITE -> SiteFiles.servesOwnFiles(record) && rootOf(record).permits(accessContext, InstanceFiles.READ);
        };
    }

    private @NonNull FileRoot rootOf(@NonNull Row record) {
        return switch (this.placement) {
            case APP -> FileRoot.app(Objects.requireNonNull(record.get(InstanceModel.ID)));
            case SITE -> FileRoot.site(Objects.requireNonNull(record.get(SiteModel.ID)));
        };
    }

    private @NonNull String nameOf(@NonNull Row record) {
        return switch (this.placement) {
            case APP -> Objects.toString(record.get(InstanceModel.NAME), "");
            case SITE -> Objects.toString(record.get(SiteModel.NAME), "");
        };
    }

    /** @return the login's mount: an app's id, a site's code */
    private @NonNull String mountOf(@NonNull FileRoot root) {
        return switch (this.placement) {
            case APP -> String.valueOf(root.recordId());
            case SITE -> Objects.requireNonNullElse(
                RecordCodes.codeUnjudged(SiteModel.MODEL_ID, String.valueOf(root.recordId())), "");
        };
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row record) {
        Conduit conduit = request.conduit();
        AccessContext accessContext = request.access();
        FileRoot root = this.rootOf(record);
        InstanceFiles files = new InstanceFiles();

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", this.nameOf(record));
        vars.put("instanceName", this.nameOf(record));
        vars.put("instanceId", root.recordId());
        vars.put("head", recordHead(conduit));
        vars.put("returnUrl", ReturnTarget.capture(conduit));
        // AIDEV-NOTE: the hidden field NAME comes from the framework constant --
        // ReturnTarget is server-only, so the common template cannot reach it.
        vars.put("returnParam", ReturnTarget.PARAM);
        vars.put("actionTarget", this.actionTarget(root));
        vars.put("canWrite", root.permits(accessContext, InstanceFiles.WRITE));
        vars.put("maxFileSize", Wording.bytes(conduit.getReader(), InstanceFiles.maxFileBytes(),
            conduit.getMessageResolver()));

        vars.put("entries", List.of());
        vars.put("crumbs", List.of());
        vars.put("volumes", List.of());
        vars.put("path", "");
        vars.put("parentPath", "");
        vars.put("editPath", "");
        vars.put("editContent", "");
        vars.put("editTooLarge", false);
        this.putSftpCard(vars, conduit, accessContext, root);

        String panel = request.panelSlug();
        Endpoint.declareQueryOn(conduit, MalformedQuery.ABSENT,
            List.of(HohenheimParams.FILES_PATH, HohenheimParams.FILES_EDIT));
        String requested = conduit.getParameter(HohenheimParams.FILES_PATH);
        String editing = conduit.getParameter(HohenheimParams.FILES_EDIT);
        try {
            // No volume is a state of the workload, not a refusal: it keeps no files to browse.
            if (files.volumeRoots(root).isEmpty()) {
                vars.put("noVolumes", true);
                return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_FILES, vars);
            }
            InstanceFiles.Listing listing = files.list(root, requested);
            vars.put("path", listing.path());
            vars.put("volumes", this.browseTargets(panel, root, listing.volumeRoots()));
            vars.put("crumbs", this.crumbsOf(panel, root, listing));
            String parent = parentWithin(listing);
            vars.put("parentPath", parent);
            vars.put("parentTarget", parent.isEmpty() ? null : this.browseTarget(panel, root, parent));
            List<Map<String, Object>> entries = new ArrayList<>();
            for (InstanceFiles.Entry entry : listing.entries()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", entry.name());
                row.put("path", entry.path());
                row.put("kind", entry.kind());
                row.put("directory", "DIRECTORY".equals(entry.kind()));
                // A symlink is SHOWN (hiding it would be a lie about what is on the volume)
                // but never followed, and every action on it is refused by the containment
                // walk -- so it renders as an inert row.
                row.put("symlink", "SYMLINK".equals(entry.kind()));
                row.put("size", entry.size());
                row.put("mode", entry.mode());
                row.put("managed", entry.managed());
                row.put("editable", "FILE".equals(entry.kind()) && !entry.managed()
                    && entry.size() <= INLINE_EDIT_LIMIT);
                row.put("browseTarget", this.browseTarget(panel, root, entry.path()));
                row.put("editTarget", this.editTarget(panel, root, listing.path(), entry.path()));
                row.put("downloadTarget", this.downloadTarget(root, entry.path()));
                entries.add(row);
            }
            vars.put("entries", entries);

            if (editing != null && !editing.isEmpty()) {
                vars.put("editPath", editing);
                vars.put("editContent", files.readText(root, editing));
            }
        } catch (Violations refused) {
            vars.put("error", HandlerSupport.messageOf(conduit, refused));
        }
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_FILES, vars);
    }

    private @NonNull RouteTarget actionTarget(@NonNull FileRoot root) {
        return switch (this.placement) {
            case APP -> HohenheimEndpoints.INSTANCE_FILE_ACTION.with(HohenheimEndpoints.INSTANCE_ID, root.recordId());
            case SITE -> HohenheimEndpoints.SITE_FILE_ACTION.with(HohenheimEndpoints.SITE_ID, root.recordId());
        };
    }

    private @NonNull RouteTarget downloadTarget(@NonNull FileRoot root, @NonNull String path) {
        return switch (this.placement) {
            case APP -> HohenheimEndpoints.INSTANCE_FILE_DOWNLOAD.with(HohenheimEndpoints.INSTANCE_ID, root.recordId())
                .with(HohenheimParams.FILES_PATH, path);
            case SITE -> HohenheimEndpoints.SITE_FILE_DOWNLOAD.with(HohenheimEndpoints.SITE_ID, root.recordId())
                .with(HohenheimParams.FILES_PATH, path);
        };
    }

    /**
     * The "Connect with SFTP" card: where to connect, the viewer's own login name for this app or site, which of the
     * viewer's credentials sign in, and the ways to make one.
     *
     * AIDEV-NOTE: it decides nothing. The SFTP server asks the same capabilities on every request; an SFTP
     * password is minted on zenit-auth's own API key page (which refuses a scope the viewer does not hold), so the
     * card names the scopes to paste there and asks for files.write only when this page found it. It LINKS there and
     * does not post the mint itself: a hawkeye form POST from this page to that page reloaded this page and dropped
     * the one-time key (seen 2026-10-08). The sign-in password never works over SFTP (it would bypass two-step
     * sign-in), which the card says.
     */
    private void putSftpCard(@NonNull Map<String, Object> vars, @NonNull Conduit conduit,
                             @NonNull AccessContext accessContext, @NonNull FileRoot root) {
        boolean enabled = HohenheimSettings.isOn(HohenheimSettings.Sftp.ENABLED);
        SftpServer server = HohenheimSftp.server();
        boolean canWrite = Boolean.TRUE.equals(vars.get("canWrite"));
        vars.put("sftpShown", true);
        vars.put("sftpEnabled", enabled);
        vars.put("sftpRunning", server != null);
        vars.put("sftpOperator", HohenheimAccess.isAdmin(accessContext));
        vars.put("sftpSettingsTarget", AttentionCollector.sftpSettingsTarget());
        if (!enabled) {
            return;
        }
        String host = HohenheimSftp.publicHost();
        vars.put("sftpHost", host != null ? host : Objects.toString(DeleteImpact.arrivalHostname(conduit), ""));
        vars.put("sftpPort", String.valueOf(server != null ? server.port() : HohenheimSftp.enabledPort()));
        String fingerprint = SftpHostKeys.fingerprint();
        vars.put("sftpFingerprint", fingerprint == null ? "" : fingerprint);

        String mount = this.mountOf(root);
        PrincipalRef account = accessContext.principal().reference();
        Row user = account == null ? null : AccountRows.currentEnabledByPrincipalId(account.id());
        String email = user == null ? null : user.get(UserModel.EMAIL);
        vars.put("sftpUsername", email == null || mount.isEmpty() ? "" : InstanceSftpRealm.username(email, mount));
        List<String> keys = new ArrayList<>();
        if (user != null) {
            for (Row key : SshKeys.of(user.get(UserModel.ID))) {
                keys.add('"' + key.get(SshKeyModel.LABEL) + '"');
            }
        }
        vars.put("sftpKeys", String.join(", ", keys));
        // "Create an SFTP password" mints it in place: the account's API key form, posted with this record's scopes
        // and name, answers the one-time password on the account page.
        vars.put("sftpPasswordTarget", AuthEndpoints.POST_ACCOUNT_API_KEY_CREATE);
        vars.put("sftpPasswordScopes", String.join(" ", switch (this.placement) {
            case APP -> InstanceSftpRealm.passwordScopes(accessContext, canWrite);
            case SITE -> InstanceSftpRealm.sitePasswordScopes(accessContext);
        }));
        vars.put("sftpPasswordLabel", "SFTP " + vars.get("instanceName"));
        vars.put("sftpKeysTarget", AuthEndpoints.GET_ACCOUNT_SSH_KEYS);
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (SftpSessionInfo session : HohenheimSftp.openOn(accessContext, mount)) {
            Map<String, Object> shown = new HashMap<>();
            shown.put("id", session.id());
            shown.put("who", session.attribution());
            shown.put("address", Objects.toString(session.address(), ""));
            shown.put("since", session.since().toString());
            shown.put("operations", session.operations());
            sessions.add(shown);
        }
        vars.put("sftpSessions", sessions);
        vars.put("timeWording", RelativeTimeWording.resolve(conduit));
    }

    /** One crumb per path segment from the volume root down, each a browsable target. */
    private @NonNull List<Map<String, Object>> crumbsOf(@NonNull String panel, @NonNull FileRoot root,
                                                        InstanceFiles.@NonNull Listing listing) {
        List<Map<String, Object>> crumbs = new ArrayList<>();
        String top = rootOf(listing);
        StringBuilder current = new StringBuilder("/".equals(top) ? "" : top);
        crumbs.add(this.crumb(panel, root, top, top));
        String remainder = listing.path().substring(top.length());
        for (String segment : remainder.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current.append('/').append(segment);
            crumbs.add(this.crumb(panel, root, segment, current.toString()));
        }
        return crumbs;
    }

    private @NonNull Map<String, Object> crumb(@NonNull String panel, @NonNull FileRoot root, @NonNull String label,
                                               @NonNull String path) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("label", label);
        entry.put("target", this.browseTarget(panel, root, path));
        return entry;
    }

    /** One {@code label}/{@code target} pair per volume root, for the volume breadcrumb. */
    private @NonNull List<Map<String, Object>> browseTargets(@NonNull String panel, @NonNull FileRoot root,
                                                             @NonNull List<String> roots) {
        List<Map<String, Object>> volumes = new ArrayList<>();
        for (String top : roots) {
            volumes.add(this.crumb(panel, root, top, top));
        }
        return volumes;
    }

    /** This tab, browsing one directory. */
    private @NonNull RouteTarget browseTarget(@NonNull String panel, @NonNull FileRoot root, @NonNull String path) {
        return this.subpageTarget(panel, root).with(HohenheimParams.FILES_PATH, path);
    }

    /** This tab, browsing {@code directory} with {@code file} open in the inline editor. */
    private @NonNull RouteTarget editTarget(@NonNull String panel, @NonNull FileRoot root, @NonNull String directory,
                                            @NonNull String file) {
        return this.subpageTarget(panel, root)
            .with(HohenheimParams.FILES_PATH, directory)
            .with(HohenheimParams.FILES_EDIT, file);
    }

    private @NonNull BoundEndpoint<?> subpageTarget(@NonNull String panel, @NonNull FileRoot root) {
        return CmsRoutes.subpage(panel, switch (this.placement) {
            case APP -> HohenheimSlugs.INSTANCES;
            case SITE -> HohenheimSlugs.SITES;
        }, root.recordId(), HohenheimSlugs.Tab.FILES);
    }

    /** The parent path, clamped at the volume root (never "" and never outside it). */
    private static @NonNull String parentWithin(InstanceFiles.@NonNull Listing listing) {
        String root = rootOf(listing);
        if (listing.path().equals(root)) {
            return "";
        }
        return PosixPaths.parentOf(listing.path());
    }

    private static @NonNull String rootOf(InstanceFiles.@NonNull Listing listing) {
        for (String root : listing.volumeRoots()) {
            if (listing.path().equals(root) || listing.path().startsWith("/".equals(root) ? root : root + "/")) {
                return root;
            }
        }
        return listing.path();
    }

}
