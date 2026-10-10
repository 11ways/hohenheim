package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * THE POSIX permission bits (the low nine mode bits) as octal text and as JDK permission sets.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class PermissionBits {

    /** Octal permission text an operator may type: three digits, optionally led by a zero; no special bits. */
    public static final Pattern TEXT = Pattern.compile("0?[0-7]{3}");

    private static final int PERMISSIONS = 0777;

    private PermissionBits() {
    }

    /** @return the permission bits of {@code mode} as 4-digit octal, e.g. {@code 0644}; type and special bits drop */
    public static @NonNull String octal(long mode) {
        return String.format("%04o", mode & PERMISSIONS);
    }

    /**
     * The permission bits of {@code mode} as a JDK set; type and special bits drop.
     *
     * AIDEV-NOTE: relies on {@link PosixFilePermission}'s declared order (owner rwx, group rwx, others rwx), which
     * is bit 8 down to bit 0.
     */
    public static @NonNull Set<PosixFilePermission> posix(int mode) {
        Set<PosixFilePermission> result = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] order = PosixFilePermission.values();
        for (int i = 0; i < order.length; i++) {
            if ((mode & (1 << (order.length - 1 - i))) != 0) {
                result.add(order[i]);
            }
        }
        return result;
    }
}
