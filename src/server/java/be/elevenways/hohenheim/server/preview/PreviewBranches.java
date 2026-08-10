package be.elevenways.hohenheim.server.preview;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-branch preview opt-in: which pushed branches a site declared preview-worthy.
 *
 * Patterns are exact branch names or globs where {@code *} matches any run of
 * characters ({@code feature/*}, {@code release-*}). Matching is case-sensitive like
 * git refs themselves. An EMPTY declaration matches nothing -- branch previews are
 * opt-in per pattern, never a default (see GitSourceSchema.PREVIEW_BRANCHES).
 */
public final class PreviewBranches {

    private PreviewBranches() {
    }

    /** The declared patterns of one site's git source settings; never null. */
    public static @NonNull List<String> patternsOf(@Nullable Object previewBranches) {
        List<String> patterns = new ArrayList<>();
        if (previewBranches instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null && !entry.toString().isBlank()) {
                    patterns.add(entry.toString().trim());
                }
            }
        }
        return patterns;
    }

    /** Whether any declared pattern covers the branch; empty patterns cover nothing. */
    public static boolean matches(@NonNull List<String> patterns, @NonNull String branch) {
        if (branch.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (matchesOne(pattern, branch)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One glob against one branch, iteratively (greedy-star with backtracking) --
     * deliberately not a regex, so no pattern character needs escaping and a hostile
     * pattern cannot smuggle regex syntax.
     */
    static boolean matchesOne(@NonNull String pattern, @NonNull String branch) {
        int p = 0;
        int b = 0;
        int starP = -1;
        int starB = -1;
        while (b < branch.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                starP = p++;
                starB = b;
            } else if (p < pattern.length() && pattern.charAt(p) == branch.charAt(b)) {
                p++;
                b++;
            } else if (starP >= 0) {
                p = starP + 1;
                b = ++starB;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
