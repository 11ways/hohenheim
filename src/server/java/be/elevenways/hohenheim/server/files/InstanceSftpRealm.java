package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.sftp.server.SftpMount;
import be.elevenways.zenit.sftp.server.SftpRealm;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What an SFTP login's mount name means here: an app's id, reached exactly as the automation API reaches it, or a
 * site's code (S27) for a static-files site serving its own files.
 *
 * AIDEV-NOTE: the login {@code <email>.<instance id>} names an app by id because names are user words and not
 * unique; a site is named by its code, which starts with a letter, so the two can never collide and every app login
 * keeps working. {@link #mount} answers null for an id that is not canonical digits, absent, trashed, generated or not
 * readable alike ({@link InstanceFiles#reachableInstance}), and for a code naming no live own-files site the caller
 * manages ({@link #reachableSite}), so probing reveals nothing. zenit-sftp asks it at login and again at every
 * request, and the mount it answers lives for that one request: nothing is cached past it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceSftpRealm implements SftpRealm {

    /** A site's manage, in API key grammar: who manages a site reaches its own files. */
    private static final String SITE_SCOPE = CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimCapabilities.MANAGE);

    /**
     * What an SSH key login is narrowed to: the instance tier's gate, the files of every app (which only an operator
     * holds, so a tenant's key is never widened by it), the file manager's two capabilities and a site's manage, in
     * API key grammar.
     *
     * AIDEV-NOTE: the gate is carried because a narrowed credential DENIES every permission it does not declare, and
     * an explicit gate denial precedes every row, the narrowed type-level row included: an operator's files key
     * without it reached nothing, not even an app the operator held a files grant on. Site manage is carried for a
     * site's own files: over SFTP a credential reaches nothing but files, whatever its scopes.
     */
    public static final List<String> FILE_SCOPES = List.of(ManagePanel.ACCESS.value(),
        HohenheimAccess.FILES_ALL.value(), scopeOf(InstanceFiles.READ), scopeOf(InstanceFiles.WRITE), SITE_SCOPE);

    /** The permissions an SFTP credential carries beside its capabilities, as far as its holder holds them. */
    private static final List<Permission> PERMISSIONS = List.of(ManagePanel.ACCESS, HohenheimAccess.FILES_ALL);

    private final long maxFileBytes;

    /** @param maxFileBytes the largest file an upload lands, the server's own read cap */
    public InstanceSftpRealm(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public @Nullable SftpMount mount(@NonNull Principal principal, @NonNull String mountName) {
        AccessContext access = AccessContext.detached(principal);
        FileRoot root;
        Integer instanceId = instanceIdOf(mountName);
        if (instanceId != null) {
            if (InstanceFiles.reachableInstance(access, instanceId) == null) {
                return null;
            }
            root = FileRoot.app(instanceId);
        } else {
            Integer siteId = reachableSite(access, mountName);
            if (siteId == null) {
                return null;
            }
            root = FileRoot.site(siteId);
        }
        InstanceFiles files = new InstanceFiles();
        return new InstanceSftpMount(root, files, new FileVerbs(files), this.maxFileBytes);
    }

    /**
     * The site a mount name's code names, when it is a live static-files site serving its own files and the caller
     * manages it.
     *
     * @return its id, or null for every refusal alike
     */
    static @Nullable Integer reachableSite(@NonNull AccessContext access, @NonNull String mountName) {
        String key = RecordCodes.keyOfCode(SiteModel.MODEL_ID, mountName);
        Integer siteId = key == null ? null : RawValues.parsedInt(key);
        if (siteId == null || !HohenheimAccess.canManageSite(access, siteId)) {
            return null;
        }
        try {
            SiteFiles.requireSite(siteId);
        } catch (Violations off) {
            return null;
        }
        return siteId;
    }

    @Override
    public @NonNull List<String> keyScopes() {
        return FILE_SCOPES;
    }

    /**
     * The scopes an SFTP password minted by this holder carries: the gate and the files of every app as far as the
     * holder holds them (a key never asks for more than its holder has, or minting it is refused), then read, and
     * write for a holder who may change files.
     */
    public static @NonNull List<String> passwordScopes(@NonNull AccessContext holder, boolean write) {
        List<String> scopes = heldPermissions(holder);
        scopes.add(scopeOf(InstanceFiles.READ));
        if (write) {
            scopes.add(scopeOf(InstanceFiles.WRITE));
        }
        return List.copyOf(scopes);
    }

    /** The scopes an SFTP password for a site's own files carries: the held permissions and a site's manage. */
    public static @NonNull List<String> sitePasswordScopes(@NonNull AccessContext holder) {
        List<String> scopes = heldPermissions(holder);
        scopes.add(SITE_SCOPE);
        return List.copyOf(scopes);
    }

    private static @NonNull List<String> heldPermissions(@NonNull AccessContext holder) {
        List<String> scopes = new ArrayList<>();
        for (Permission permission : PERMISSIONS) {
            if (holder.hasPermission(permission)) {
                scopes.add(permission.value());
            }
        }
        return scopes;
    }

    /** @return the login name an account signs in to an app's files (its id) or a site's (its code) with */
    public static @NonNull String username(@NonNull String email, @NonNull String mountName) {
        return email + "." + mountName;
    }

    /** @return the instance id a mount name spells canonically (digits, no sign, no leading zero), else null */
    static @Nullable Integer instanceIdOf(@NonNull String mountName) {
        if (mountName.isEmpty() || mountName.length() > 9 || mountName.charAt(0) == '0') {
            return null;
        }
        for (int i = 0; i < mountName.length(); i++) {
            if (mountName.charAt(i) < '0' || mountName.charAt(i) > '9') {
                return null;
            }
        }
        return Integer.parseInt(mountName);
    }

    private static @NonNull String scopeOf(@NonNull String capability) {
        return CapabilityScopes.format(InstanceModel.MODEL_ID, capability);
    }
}
