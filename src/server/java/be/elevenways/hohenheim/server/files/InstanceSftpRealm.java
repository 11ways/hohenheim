package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.sftp.server.SftpMount;
import be.elevenways.zenit.sftp.server.SftpRealm;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What an SFTP login's mount name means here: an instance id, reached exactly as the automation API reaches it.
 *
 * AIDEV-NOTE: the login {@code <email>.<instance id>} names the instance by id because names are user words and not
 * unique. {@link #mount} answers null for an id that is not canonical digits, absent, trashed, generated or not
 * readable alike ({@link InstanceFiles#reachableInstance}), so probing ids reveals nothing. zenit-sftp asks it at
 * login and again at every request, and the mount it answers lives for that one request: nothing is cached past it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceSftpRealm implements SftpRealm {

    /**
     * What an SSH key login is narrowed to: the instance tier's gate, the files of every app (which only an operator
     * holds, so a tenant's key is never widened by it) and the file manager's two capabilities, in API key grammar.
     *
     * AIDEV-NOTE: the gate is carried because a narrowed credential DENIES every permission it does not declare, and
     * an explicit gate denial precedes every row, the narrowed type-level row included: an operator's files key
     * without it reached nothing, not even an app the operator held a files grant on.
     */
    public static final List<String> FILE_SCOPES = List.of(ManagePanel.ACCESS.value(),
        HohenheimAccess.FILES_ALL.value(), scopeOf(InstanceFiles.READ), scopeOf(InstanceFiles.WRITE));

    /** The permissions an SFTP credential carries beside its capabilities, as far as its holder holds them. */
    private static final List<Permission> PERMISSIONS = List.of(ManagePanel.ACCESS, HohenheimAccess.FILES_ALL);

    private final long maxFileBytes;

    /** @param maxFileBytes the largest file an upload lands, the server's own read cap */
    public InstanceSftpRealm(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public @Nullable SftpMount mount(@NonNull Principal principal, @NonNull String mountName) {
        Integer instanceId = instanceIdOf(mountName);
        if (instanceId == null || InstanceFiles.reachableInstance(AccessContext.detached(principal),
                instanceId) == null) {
            return null;
        }
        InstanceFiles files = new InstanceFiles();
        return new InstanceSftpMount(instanceId, files, new FileVerbs(files), this.maxFileBytes);
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
        List<String> scopes = new ArrayList<>();
        for (Permission permission : PERMISSIONS) {
            if (holder.hasPermission(permission)) {
                scopes.add(permission.value());
            }
        }
        scopes.add(scopeOf(InstanceFiles.READ));
        if (write) {
            scopes.add(scopeOf(InstanceFiles.WRITE));
        }
        return List.copyOf(scopes);
    }

    /** @return the login name an account signs in to an instance's files with */
    public static @NonNull String username(@NonNull String email, int instanceId) {
        return email + "." + instanceId;
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
