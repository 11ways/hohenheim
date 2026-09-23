package be.elevenways.hohenheim.test.backup;

import be.elevenways.hohenheim.server.backup.SshBackupTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An ssh target's base path is normalized once, at construction: {@code list()} matches the
 * remote {@code find} output against {@code base + "/"}, so a configured base with a trailing
 * slash used to match nothing -- list() answered empty for a target full of archives and
 * retention never pruned one.
 */
class SshBackupTargetPathTest {

    @Test
    void aTrailingSlashNeverHidesTheArchivesUnderTheBase() {
        // 1. Trailing slashes go; the listing prefix is then exactly "/backups/".
        assertThat(SshBackupTarget.normalizedBase("/backups/"))
            .as("step 1: one trailing slash is dropped").isEqualTo("/backups");
        assertThat(SshBackupTarget.normalizedBase("/backups///"))
            .as("step 1: and several").isEqualTo("/backups");

        // 2. A clean base, surrounding whitespace, and the root itself.
        assertThat(SshBackupTarget.normalizedBase("/srv/hohenheim"))
            .as("step 2: a clean base is unchanged").isEqualTo("/srv/hohenheim");
        assertThat(SshBackupTarget.normalizedBase("  /srv/x/ "))
            .as("step 2: whitespace is not part of a path").isEqualTo("/srv/x");
        assertThat(SshBackupTarget.normalizedBase("/"))
            .as("step 2: the root stays the root").isEqualTo("/");
    }
}
