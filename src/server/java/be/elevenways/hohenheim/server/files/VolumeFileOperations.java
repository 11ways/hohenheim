package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.server.instance.VolumeDeclarationOperations;
import be.elevenways.protoblast.common.key.IdentityKey;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The handler of {@link VolumeOperations#PUT_FILE}: the file manager's write lane into the subject volume, recorded as
 * an upload.
 *
 * AIDEV-NOTE: the bytes ride {@link #UPLOAD}, attached by the HTTP lane only after {@code OperationPipeline.validate}
 * admitted the caller, so no body is read for a caller the operation refuses. It is no {@code requiresAttachment}:
 * that preflight runs before the file exists.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class VolumeFileOperations {

    /** The spooled file a put writes. */
    public static final IdentityKey<Path> UPLOAD = IdentityKey.create("hohenheim_volume_put_upload");

    /** The capability on the volume's app a put asks, beside the admin permission. */
    private static final String OWNER_CAPABILITY = HohenheimCapabilities.FILES_WRITE;

    static {
        OperationHandlers.attach(VolumeOperations.PUT_FILE)
            .authorize((volume, input, access) -> VolumeDeclarationOperations.ownerRefusal(access,
                volume.get(InstanceVolumeModel.INSTANCE_ID), OWNER_CAPABILITY))
            .handle(call -> {
                Row volume = call.subject();
                Path upload = call.attachment(UPLOAD);
                if (upload == null) {
                    throw new IllegalStateException(VolumeOperations.PUT_FILE.id()
                        + " carries its file as the UPLOAD attachment");
                }
                new FileVerbs().putInto(volume.get(InstanceVolumeModel.INSTANCE_ID),
                    volume.get(InstanceVolumeModel.CONTAINER_PATH), Objects.requireNonNull(call.input()).path(),
                    upload, InstanceFiles.maxFileBytes());
                return null;
            });
    }

    private VolumeFileOperations() {
    }

    /** Attaches the handler; idempotent. */
    public static void init() {
    }

    /**
     * What the put refuses the caller on every volume of the app, asked before the named volume is looked up so a
     * known and an unknown name answer alike.
     *
     * @return the refusal, or null when the caller may go on to name a volume
     */
    static @Nullable DomainRefusal refusalOnApp(@NonNull AccessContext access, int instanceId) {
        return VolumeDeclarationOperations.refusalOnApp(VolumeOperations.PUT_FILE, access, instanceId,
            OWNER_CAPABILITY);
    }
}
