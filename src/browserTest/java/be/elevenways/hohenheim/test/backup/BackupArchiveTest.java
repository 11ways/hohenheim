package be.elevenways.hohenheim.test.backup;

import be.elevenways.hohenheim.server.backup.BackupArchive;
import be.elevenways.hohenheim.server.backup.BackupManifest;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The archive format's honesty, file-level and daemon-free: an encrypted round trip
 * (settings, control-plane profile and payload), the VERSION contract in both
 * directions (an older manifest reads, a newer one is refused), then every refusal
 * lane -- a flipped ciphertext byte (GCM), a flipped payload checksum (manifest pin),
 * and a foreign keyring. Every refusal must happen inside openVerified, which is what
 * runs BEFORE any live state changes.
 */
class BackupArchiveTest {

    @TempDir
    Path tmp;

    private BackupManifest manifest(String sha, long size) {
        return new BackupManifest(BackupManifest.FORMAT_VERSION, "2026-08-03T00:00:00Z",
            "test", "fixture", "hohenheim:docker_container",
            BackupManifest.PAYLOAD_VOLUME_TARS,
            Map.of("image", "alpine", "environment_variables", Map.of("SECRET", "s3cret")),
            "alpine:latest", "sha256:abc", "", 8080, "tcp",
            List.of(new BackupManifest.VolumeEntry("data", "/data", "data.tar", sha, size)),
            new BackupManifest.InstanceProfile(
                new BackupManifest.TemplateRef(7, "velocity", 3),
                "restart", "staging", "offsite",
                List.of(new BackupManifest.VariableEntry("FORWARDING_SECRET", "secret", "f0rward"),
                    new BackupManifest.VariableEntry("MOTD", "plain", "hello")),
                List.of(new BackupManifest.FileEntry("/config/velocity.toml",
                        "secret = \"{{FORWARDING_SECRET}}\"", "0640", null),
                    new BackupManifest.FileEntry("/config/forced-hosts.toml",
                        "generated", "0644", "game_domain"))));
    }

    private Path buildArchive(EncryptionKeyring keyring, byte[] payload) throws IOException {
        Path tar = tmp.resolve("data.tar");
        Files.write(tar, payload);
        Path archive = tmp.resolve("archive.hib");
        BackupArchive.create(manifest(BackupArchive.sha256Of(tar), payload.length),
            Map.of("data.tar", tar), archive, keyring);
        return archive;
    }

    @Test
    void encryptedRoundTripCarriesManifestAndPayloadIntact() throws IOException {
        EncryptionKeyring keyring = EncryptionKeyring.loadOrCreate(tmp.resolve("ring.keys"));
        byte[] payload = "volume-bytes".getBytes(StandardCharsets.UTF_8);

        // 1. Create and re-open.
        Path archive = buildArchive(keyring, payload);
        assertThat(Files.readAllBytes(archive))
            .as("step 1: the archive on disk is NOT the plaintext zip (no PK zip magic)")
            .satisfies(bytes -> assertThat(bytes[0]).isNotEqualTo((byte) 'P'));
        BackupArchive.Opened opened = BackupArchive.openVerified(archive, tmp, keyring);

        // 2. Manifest round-trips, secrets included (they are inside the encryption).
        assertThat(opened.manifest().instanceName())
            .as("step 2: manifest name survives").isEqualTo("fixture");
        assertThat(opened.manifest().settings().get("environment_variables"))
            .as("step 2: secret variables ride the encrypted manifest")
            .isInstanceOfSatisfying(Map.class,
                env -> assertThat(env.get("SECRET")).isEqualTo("s3cret"));

        // 3. The control-plane PROFILE round-trips too: the template binding, the crash
        //    policy and -- the part the settings map never carried -- the variable rows
        //    (secret value included) and the config-file rows with their attribution.
        BackupManifest.InstanceProfile profile = opened.manifest().profile();
        assertThat(profile).as("step 3: a version-2 manifest carries a profile").isNotNull();
        assertThat(profile.template())
            .as("step 3: the template binding survives with its name AND catalog version")
            .isEqualTo(new BackupManifest.TemplateRef(7, "velocity", 3));
        assertThat(profile.crashPolicy())
            .as("step 3: the crash policy survives instead of defaulting back to none")
            .isEqualTo("restart");
        assertThat(profile.variables())
            .as("step 3: the variable inventory survives, secret value included")
            .containsExactly(
                new BackupManifest.VariableEntry("FORWARDING_SECRET", "secret", "f0rward"),
                new BackupManifest.VariableEntry("MOTD", "plain", "hello"));
        assertThat(profile.files())
            .as("step 3: the config-file inventory survives, generated attribution included")
            .containsExactly(
                new BackupManifest.FileEntry("/config/velocity.toml",
                    "secret = \"{{FORWARDING_SECRET}}\"", "0640", null),
                new BackupManifest.FileEntry("/config/forced-hosts.toml",
                    "generated", "0644", "game_domain"));

        // 4. Payload extracts byte-identical.
        Map<String, Path> tars = BackupArchive.extractVolumes(opened, tmp.resolve("out"));
        assertThat(Files.readAllBytes(tars.get("data")))
            .as("step 4: the extracted payload is byte-identical").isEqualTo(payload);
        Files.deleteIfExists(opened.zip());
    }

    /**
     * The v1 read path: hohenheim has live installations, so archives written before the
     * profile existed are on real backup targets right now. Reading one must neither
     * crash nor invent -- it parses into a manifest whose profile is null, which SAYS
     * "this archive declares nothing about templates, variables or config files" rather
     * than "the source had none". A version this build cannot read is still refused
     * whole, which is the half of the old strictness worth keeping.
     */
    @Test
    void aVersionOneManifestReadsWithoutItsProfileAndAFutureVersionIsRefused()
            throws IOException {
        // 1. A v1 manifest map, exactly as the pre-profile build wrote it.
        Map<String, Object> v1 = new LinkedHashMap<>(manifest("0".repeat(64), 0).toMap());
        v1.put("version", BackupManifest.VERSION_WITHOUT_PROFILE);
        v1.remove("variables");
        v1.remove("files");
        BackupManifest parsed = BackupManifest.fromMap(v1);
        assertThat(parsed.version())
            .as("step 1: the older format is READ, not refused")
            .isEqualTo(BackupManifest.VERSION_WITHOUT_PROFILE);
        assertThat(parsed.settings().get("image"))
            .as("step 1: and everything v1 DID carry still arrives").isEqualTo("alpine");
        assertThat(parsed.profile())
            .as("step 1: THE POINT -- a v1 manifest declares nothing rather than"
                + " declaring zero variables, so a restore can tell the two apart")
            .isNull();

        // 2. A manifest from a FUTURE build stays a whole-archive refusal: fields this
        //    build recognises may not mean here what they meant there.
        Map<String, Object> future = new LinkedHashMap<>(manifest("0".repeat(64), 0).toMap());
        future.put("version", BackupManifest.FORMAT_VERSION + 1);
        assertThat(catchThrowable(() -> BackupManifest.fromMap(future)))
            .as("step 2: a newer manifest version is refused, naming what is readable")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("reads versions 1 to " + BackupManifest.FORMAT_VERSION);
    }

    @Test
    void aFlippedCiphertextByteIsRefusedByAuthentication() throws IOException {
        EncryptionKeyring keyring = EncryptionKeyring.loadOrCreate(tmp.resolve("ring.keys"));
        Path archive = buildArchive(keyring, "payload".getBytes(StandardCharsets.UTF_8));

        // Flip one byte in the ciphertext body (past the header).
        byte[] bytes = Files.readAllBytes(archive);
        bytes[bytes.length - 20] ^= 0x01;
        Files.write(archive, bytes);

        Throwable refusal = catchThrowable(() -> BackupArchive.openVerified(archive, tmp, keyring));
        assertThat(refusal)
            .as("a flipped byte is an authentication refusal naming the corruption")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("refused whole");
    }

    @Test
    void aForeignKeyringIsRefusedByName() throws IOException {
        EncryptionKeyring writer = EncryptionKeyring.loadOrCreate(tmp.resolve("writer.keys"));
        EncryptionKeyring stranger = EncryptionKeyring.loadOrCreate(tmp.resolve("stranger.keys"));
        Path archive = buildArchive(writer, "payload".getBytes(StandardCharsets.UTF_8));

        Throwable refusal = catchThrowable(() -> BackupArchive.openVerified(archive, tmp, stranger));
        assertThat(refusal)
            .as("a keyring without the writing key refuses, naming the key")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("keyring does not contain");
    }

    @Test
    void aPayloadWhoseChecksumDisagreesWithTheManifestIsRefused() throws IOException {
        EncryptionKeyring keyring = EncryptionKeyring.loadOrCreate(tmp.resolve("ring.keys"));
        // Manifest LIES about the payload checksum: the archive encrypts fine, but
        // verification must catch the disagreement.
        Path tar = tmp.resolve("data.tar");
        Files.write(tar, "actual-bytes".getBytes(StandardCharsets.UTF_8));
        Path archive = tmp.resolve("archive.hib");
        BackupArchive.create(manifest("0".repeat(64), 12), Map.of("data.tar", tar),
            archive, keyring);

        Throwable refusal = catchThrowable(() -> BackupArchive.openVerified(archive, tmp, keyring));
        assertThat(refusal)
            .as("a manifest/payload checksum disagreement is a whole-archive refusal")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("does not match its recorded checksum");
    }
}
