package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.spamservice.client.ManagedClient;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.SampleDetail;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.ForeignCodes;
import be.elevenways.zenit.server.operation.RecordReadLines;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The spam service's own codes (SPC clients, SPK keys, SPS samples) as kinds a read names: each read asks the service,
 * an administrator alone is shown one, and Hohenheim keeps no copy.
 *
 * AIDEV-NOTE: a read's lines are the settings spam_client_set takes by its argument names, and a key's or a sample's
 * own facts; the client they belong to is named by its code, never by the service's id. A service that does not
 * answer refuses the read in the spam tools' words (SpamserviceRemoteStore.found), never as a record naming nothing.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SpamRecordCodes {

    /** The entry naming the client a key or a sample belongs to, by the code the service gave it. */
    private static final String CLIENT = SpamserviceClientKeysResource.SPAM_CLIENT;

    private static boolean declared;

    private SpamRecordCodes() {
    }

    /** Declares the three kinds; boot, idempotent. */
    public static synchronized void declare() {
        if (declared) return;
        declared = true;
        ForeignCodes.declare(SpamRecordCodes.class, "SPC", CLIENT, (code, access) -> {
            if (!admin(access)) return null;
            ManagedClient client = SpamserviceRemoteStore.found(SpamserviceRemoteStore.MANAGED,
                each -> each.client(code));
            return client == null ? null : lines(SpamserviceClientsResource.written(client), null);
        });
        ForeignCodes.declare(SpamRecordCodes.class, "SPK", "spam_key", (code, access) -> {
            if (!admin(access)) return null;
            ManagedClientKey key = SpamserviceRemoteStore.found(SpamserviceRemoteStore.MANAGED,
                each -> each.key(code));
            return key == null ? null : lines(SpamserviceClientKeysResource.values(key), key.clientId());
        });
        ForeignCodes.declare(SpamRecordCodes.class, "SPS", "spam_sample", (code, access) -> {
            if (!admin(access)) return null;
            SampleDetail sample = SpamserviceRemoteStore.found(SpamserviceRemoteStore.MANAGED,
                each -> each.sample(code));
            return sample == null ? null : lines(SpamserviceSamplesResource.values(sample.summary()),
                sample.summary().clientId());
        });
    }

    private static boolean admin(@NonNull AccessContext access) {
        return access.hasPermission(HohenheimSources.ADMIN_ACCESS);
    }

    /**
     * @param clientId the service's id of the client the record belongs to, named by its code instead; null for a
     *                 client itself
     * @return one line per value that says something, by name, the client by its code
     */
    private static @NonNull List<RecordReadLines.Line> lines(@NonNull Map<String, Object> values,
                                                             @Nullable Object clientId) {
        List<RecordReadLines.Line> lines = new ArrayList<>();
        if (clientId != null) {
            String client = SpamserviceClientsResource.codeOf(String.valueOf(clientId));
            if (client != null) lines.add(new RecordReadLines.Line(CLIENT, client));
        }
        new TreeMap<>(values).forEach((name, value) -> {
            String said = value == null ? "" : String.valueOf(value);
            if (!said.isBlank() && !"client_id".equals(name) && !"key".equals(name)) {
                lines.add(new RecordReadLines.Line(name, said));
            }
        });
        return lines;
    }
}
