package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.files.HohenheimSftp;
import be.elevenways.hohenheim.server.files.InstanceFiles;
import be.elevenways.hohenheim.server.files.InstanceSftpRealm;
import be.elevenways.zenit.auth.AuthEndpoints;
import be.elevenways.zenit.auth.model.SshKeyModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AccountRows;
import be.elevenways.zenit.auth.server.SshKeys;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.sftp.server.SftpHostKeys;
import be.elevenways.zenit.sftp.server.SftpServer;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Files tab on an instance: a browser over the instance's own volumes, plus an inline
 * editor for text files.
 *
 * The page makes NO authorization decision. It asks {@link InstanceFiles}, which gates on
 * {@code files.read}, and renders the write controls only when the context also holds
 * {@code files.write} -- an affordance on top of a gate, never instead of one: the action
 * endpoint asks the service for write authority regardless of what this page drew.
 */
public final class InstanceFilesPage implements RecordTab.Rendered<Row> {

    public static final String SLUG = "files";

    /** Above this, the inline editor is not offered -- a browser is not a hex editor. */
    private static final long INLINE_EDIT_LIMIT = 512 * 1024;

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_files_browser"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("files").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("folder-tree"); }

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
        // AIDEV-NOTE: deliberately NO kind gate here (unlike the Volumes tab): the file
        // lane browses the RUNTIME's volumes -- Docker named volumes included -- not only
        // declared instance_volumes rows, so a docker_container legitimately has files to
        // browse. The page itself renders the named no-lane/no-volumes states.
        return HohenheimAccess.hasInstanceCapability(
            accessContext, record.get(InstanceModel.ID), HohenheimAccess.FILES_READ);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        AccessContext accessContext = request.access();
        Integer instanceId = instance.get(InstanceModel.ID);
        InstanceFiles files = new InstanceFiles();

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("instanceId", instanceId);
        vars.put("head", recordHead(conduit));
        vars.put("returnUrl", ReturnTarget.capture(conduit));
        // AIDEV-NOTE: the hidden field NAME comes from the framework constant --
        // ReturnTarget is server-only, so the common template cannot reach it.
        vars.put("returnParam", ReturnTarget.PARAM);
        vars.put("actionTarget", HohenheimEndpoints.INSTANCE_FILE_ACTION
            .with(HohenheimEndpoints.INSTANCE_ID, instanceId));
        vars.put("canWrite", HohenheimAccess.hasInstanceCapability(accessContext, instanceId,
            HohenheimAccess.FILES_WRITE));
        vars.put("maxFileBytes", InstanceFiles.maxFileBytes());

        vars.put("entries", List.of());
        vars.put("crumbs", List.of());
        vars.put("volumes", List.of());
        vars.put("path", "");
        vars.put("parentPath", "");
        vars.put("editPath", "");
        vars.put("editContent", "");
        vars.put("editTooLarge", false);

        // A runtime that has no file lane at all is a NAMED state, not an error banner:
        // only the Docker driver implements InstanceFileSupport, so an Incus workload
        // renders "not available for this runtime yet" and no browser chrome.
        boolean supported = files.isSupported(instanceId);
        vars.put("supported", supported);
        if (!supported) {
            return new RenderTemplateResult(
                HohenheimTemplateIds.INSTANCE_FILES, vars);
        }
        putSftpCard(vars, conduit, accessContext, instanceId);

        String panel = request.panelSlug();
        String requested = conduit.getQueryParam(HohenheimParams.FILES_PATH.getName());
        String editing = conduit.getQueryParam(HohenheimParams.FILES_EDIT.getName());
        try {
            InstanceFiles.Listing listing = files.list(instanceId, requested);
            vars.put("path", listing.path());
            vars.put("volumes", browseTargets(panel, instanceId, listing.volumeRoots()));
            vars.put("crumbs", crumbsOf(panel, instanceId, listing));
            String parent = parentWithin(listing);
            vars.put("parentPath", parent);
            vars.put("parentTarget", parent.isEmpty() ? null
                : browseTarget(panel, instanceId, parent));
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
                row.put("browseTarget", browseTarget(panel, instanceId, entry.path()));
                row.put("editTarget", editTarget(panel, instanceId, listing.path(), entry.path()));
                row.put("downloadTarget", HohenheimEndpoints.INSTANCE_FILE_DOWNLOAD
                    .with(HohenheimEndpoints.INSTANCE_ID, instanceId)
                    .with(HohenheimParams.FILES_PATH, entry.path()));
                entries.add(row);
            }
            vars.put("entries", entries);

            if (editing != null && !editing.isEmpty()) {
                vars.put("editPath", editing);
                vars.put("editContent", files.readText(instanceId, editing));
            }
        } catch (Violations refused) {
            vars.put("error", HandlerSupport.messageOf(conduit, refused));
        }
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_FILES, vars);
    }

    /**
     * The "Connect with SFTP" card: where to connect, the viewer's own login name for this app, which of the viewer's
     * credentials sign in, and the ways to make one.
     *
     * AIDEV-NOTE: it decides nothing. The SFTP server asks the same files.read/files.write on every request; an SFTP
     * password is minted on zenit-auth's own API key page (which refuses a scope the viewer does not hold), so the
     * card names the scopes to paste there and asks for files.write only when this page found it. It LINKS there and
     * does not post the mint itself: a hawkeye form POST from this page to that page reloaded this page and dropped
     * the one-time key (seen 2026-10-08). The sign-in password never works over SFTP (it would bypass two-step
     * sign-in), which the card says.
     */
    private static void putSftpCard(@NonNull Map<String, Object> vars, @NonNull Conduit conduit,
                                    @NonNull AccessContext accessContext, int instanceId) {
        boolean enabled = HohenheimSftp.isEnabled();
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

        PrincipalRef account = accessContext.principal().reference();
        Row user = account == null ? null : AccountRows.currentEnabledByPrincipalId(account.id());
        String email = user == null ? null : user.get(UserModel.EMAIL);
        vars.put("sftpUsername", email == null ? "" : InstanceSftpRealm.username(email, instanceId));
        List<String> keys = new ArrayList<>();
        if (user != null) {
            for (Row key : SshKeys.of(user.get(UserModel.ID))) {
                keys.add(key.get(SshKeyModel.LABEL));
            }
        }
        vars.put("sftpKeys", String.join(", ", keys));
        vars.put("sftpPasswordTarget", AuthEndpoints.GET_ACCOUNT_API_KEYS);
        vars.put("sftpPasswordScopes", String.join(" ", InstanceSftpRealm.passwordScopes(canWrite)));
        vars.put("sftpKeysTarget", AuthEndpoints.GET_ACCOUNT_SSH_KEYS);
    }

    /** One crumb per path segment from the volume root down, each a browsable target. */
    private static @NonNull List<Map<String, Object>> crumbsOf(@NonNull String panel,
                                                               @NonNull Integer instanceId,
                                                               InstanceFiles.@NonNull Listing listing) {
        List<Map<String, Object>> crumbs = new ArrayList<>();
        String root = rootOf(listing);
        StringBuilder current = new StringBuilder(root);
        crumbs.add(crumb(panel, instanceId, root, root));
        String remainder = listing.path().substring(root.length());
        for (String segment : remainder.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current.append('/').append(segment);
            crumbs.add(crumb(panel, instanceId, segment, current.toString()));
        }
        return crumbs;
    }

    private static @NonNull Map<String, Object> crumb(@NonNull String panel,
                                                      @NonNull Integer instanceId,
                                                      @NonNull String label,
                                                      @NonNull String path) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("label", label);
        entry.put("target", browseTarget(panel, instanceId, path));
        return entry;
    }

    /** One {@code label}/{@code target} pair per volume root, for the volume breadcrumb. */
    private static @NonNull List<Map<String, Object>> browseTargets(@NonNull String panel,
                                                                    @NonNull Integer instanceId,
                                                                    @NonNull List<String> roots) {
        List<Map<String, Object>> volumes = new ArrayList<>();
        for (String root : roots) {
            volumes.add(crumb(panel, instanceId, root, root));
        }
        return volumes;
    }

    /**
     * This tab, browsing one directory.
     *
     * AIDEV-NOTE: composed off CmsEndpoints rather than CmsRoutes.subpage because a CMS
     * route PLUS a query parameter cannot be built from CmsRoutes -- its builders return
     * the RouteTarget interface, which has no with(...).
     */
    private static @NonNull RouteTarget browseTarget(@NonNull String panel,
                                                     @NonNull Integer instanceId,
                                                     @NonNull String path) {
        return subpageTarget(panel, instanceId).with(HohenheimParams.FILES_PATH, path);
    }

    /** This tab, browsing {@code directory} with {@code file} open in the inline editor. */
    private static @NonNull RouteTarget editTarget(@NonNull String panel,
                                                   @NonNull Integer instanceId,
                                                   @NonNull String directory,
                                                   @NonNull String file) {
        return subpageTarget(panel, instanceId)
            .with(HohenheimParams.FILES_PATH, directory)
            .with(HohenheimParams.FILES_EDIT, file);
    }

    private static @NonNull BoundEndpoint<Map<String, Object>> subpageTarget(@NonNull String panel,
                                                                            @NonNull Integer instanceId) {
        return CmsEndpoints.RECORD_SUBPAGE
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.INSTANCES)
            .with(CmsEndpoints.RESOURCE_ID_PARAM, String.valueOf(instanceId))
            .with(CmsEndpoints.SUBPAGE_PARAM, SLUG);
    }

    /** The parent path, clamped at the volume root (never "" and never outside it). */
    private static @NonNull String parentWithin(InstanceFiles.@NonNull Listing listing) {
        String root = rootOf(listing);
        if (listing.path().equals(root)) {
            return "";
        }
        int slash = listing.path().lastIndexOf('/');
        return slash <= 0 ? root : listing.path().substring(0, slash);
    }

    private static @NonNull String rootOf(InstanceFiles.@NonNull Listing listing) {
        for (String root : listing.volumeRoots()) {
            if (listing.path().equals(root) || listing.path().startsWith(root + "/")) {
                return root;
            }
        }
        return listing.path();
    }

}
