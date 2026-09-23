package be.elevenways.hohenheim.source;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE question "may this text be handed to git as a branch, tag or commit name", with one home.
 *
 * AIDEV-NOTE: the rules are git's own {@code check-ref-format} rules for a ONE-LEVEL-allowed
 * name ({@code --allow-onelevel}, which is what {@code clone --branch} and {@code fetch origin
 * <ref>} accept), plus two of ours: no leading {@code -} (git would read the value as an
 * OPTION -- {@code --upload-pack=...} is the classic one) and no leading {@code +} (a force
 * refspec). A ref arrives from a stored setting, a forge payload or an API caller, so every
 * one of them is somebody else's text until this says otherwise. Plain character checks, no
 * regex, because the common source set compiles for the browser too.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class GitRefNames {

    private GitRefNames() {
    }

    /** @return whether {@code ref} is a name git takes as a ref and never as an option */
    public static boolean isValid(@Nullable String ref) {

        if (ref == null || ref.isEmpty() || ref.length() > 255) {
            return false;
        }

        char first = ref.charAt(0);

        if (first == '-' || first == '+' || first == '/' || ref.endsWith("/") || ref.endsWith(".")) {
            return false;
        }

        if (ref.equals("@") || ref.contains("..") || ref.contains("//") || ref.contains("@{")) {
            return false;
        }

        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c < 0x20 || c == 0x7F || c == ' ' || c == '~' || c == '^' || c == ':'
                    || c == '?' || c == '*' || c == '[' || c == '\\') {
                return false;
            }
        }

        for (String component : ref.split("/", -1)) {
            if (component.isEmpty() || component.startsWith(".") || component.endsWith(".lock")) {
                return false;
            }
        }

        return true;
    }
}
