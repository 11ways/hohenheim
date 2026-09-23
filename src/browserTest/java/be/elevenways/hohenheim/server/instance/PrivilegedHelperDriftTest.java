package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.server.host.PrivilegedHelper;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The privileged helper exists twice -- as the script tools/install-host.sh installs and as
 * {@link PrivilegedHelper} the controller calls it through -- and this binds the two.
 *
 * AIDEV-NOTE: it lives in the instance package because the uid floor it binds is
 * {@link WorkspaceUids#LOWEST_SAFE_BASE}, package-private there. A verb the Java side sends
 * that the script does not answer is a volume operation that fails on every host; a floor
 * the two disagree on is a workspace uid the helper refuses (or a system uid it accepts).
 */
class PrivilegedHelperDriftTest {

    private static final Path SCRIPT = Path.of("tools/install-host.sh");

    /** A verb arm of the helper's dispatch: four spaces, a dashed token, a closing paren. */
    private static final Pattern VERB_ARM = Pattern.compile("(?m)^    ([a-z]+(?:-[a-z]+)+)\\)\\s*$");

    @Test
    void theInstalledHelperAnswersExactlyWhatTheControllerSends() throws IOException {
        String script = Files.readString(SCRIPT);

        // 1. The script installs the helper at the path the controller calls and the
        //    sudoers grant names.
        Path helper = Path.of(PrivilegedHelper.PATH);
        assertThat(script)
            .as("step 1: the installer's helper directory is PrivilegedHelper.PATH's")
            .contains("HELPER_DIR=\"" + helper.getParent() + "\"")
            .as("step 1: and its file name")
            .contains("HELPER_PATH=\"$HELPER_DIR/" + helper.getFileName() + "\"");

        // 2. Its dispatch answers every verb of the Java vocabulary and nothing else.
        Set<String> scriptVerbs = new TreeSet<>();
        Matcher arms = VERB_ARM.matcher(script);
        while (arms.find()) {
            scriptVerbs.add(arms.group(1));
        }
        Set<String> javaVerbs = new TreeSet<>();
        for (PrivilegedHelper.Verb verb : PrivilegedHelper.Verb.values()) {
            javaVerbs.add(verb.token());
        }
        assertThat(scriptVerbs)
            .as("step 2: the helper's verbs are exactly PrivilegedHelper.Verb's tokens")
            .isEqualTo(javaVerbs);

        // 3. One uid floor on both sides, and it is the workspace uid floor itself.
        assertThat(PrivilegedHelper.MIN_OWNER_UID)
            .as("step 3: the helper's floor is the workspace uid floor")
            .isEqualTo(WorkspaceUids.LOWEST_SAFE_BASE);
        assertThat(script)
            .as("step 3: and the script spells the same number")
            .contains("MIN_OWNER_UID=" + PrivilegedHelper.MIN_OWNER_UID + "\n");

        // 4. The Spamservice account the helper hands directories to is the one the
        //    manager insists on running as.
        assertThat(script)
            .as("step 4: the installer's spamservice account is the manager's")
            .contains("SPAMSERVICE_USER=\"" + SpamserviceManager.DEDICATED_SYSTEM_USER + "\"");

        // 5. The unrestricted pre-helper grant is never written again.
        assertThat(script)
            .as("step 5: no bare chown/chmod/rm/mkdir/btrfs grant survives")
            .doesNotContain("NOPASSWD: /usr/bin/btrfs")
            .doesNotContain("NOPASSWD: /usr/bin/chown");
    }
}
