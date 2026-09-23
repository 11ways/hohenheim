package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.migration.M011_HashBasicProviderPasswords;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.zenit.auth.server.PasswordHasher;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The server's half of M011: argon2 hashing through the credential home, installed at class-load.
 *
 * AIDEV-NOTE: an EMPTY stored password is hashed too (hashIfNeeded leaves blanks alone because a
 * blank SUBMIT means "keep"); a stored empty password was a working credential under the old
 * plaintext compare, and the migration keeps every user's password working.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
@ZenitAutoLoad
public final class BasicPasswordHashing implements M011_HashBasicProviderPasswords.PasswordHashing {

    public static final boolean LOADED = install();

    private BasicPasswordHashing() {
    }

    private static boolean install() {
        M011_HashBasicProviderPasswords.HASHING.install(new BasicPasswordHashing());
        return true;
    }

    @Override
    public @NonNull String hashIfPlaintext(@NonNull String stored) {
        if (BasicCredentials.isHashed(stored)) {
            return stored;
        }
        return stored.isBlank() ? PasswordHasher.hash(stored) : BasicCredentials.hashIfNeeded(stored);
    }
}
