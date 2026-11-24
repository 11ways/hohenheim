package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.server.auth.ProteusSignIn;
import be.elevenways.zenit.auth.model.ExternalIdentityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one way a master and a server it manages name the same person: by the Proteus sign-in both offer, as the
 * realm ({@link ProteusSignIn#realm}) and that realm's subjects.
 *
 * AIDEV-NOTE: never by an e-mail address and never by the provider slug alone: a slug is a local label, so a subject
 * is compared only between two servers answering the same realm, and only identities linked under this server's
 * Proteus slug count. One person may hold several subjects (accounts Proteus merged); a set of subjects naming more
 * than one account here names nobody.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class SharedSignIn {

    private SharedSignIn() {
    }

    /** @return the Proteus subjects linked to the account here, empty for none */
    static @NonNull List<String> subjectsOf(@NonNull UUID account) {
        List<String> subjects = new ArrayList<>();
        for (Row identity : Models.get(ExternalIdentityModel.class).find().noCache()
                .where(ExternalIdentityModel.USER_ID.eq(account))
                .where(ExternalIdentityModel.PROVIDER_SLUG.eq(ProteusSignIn.SLUG))
                .only(ExternalIdentityModel.ID, ExternalIdentityModel.SUBJECT).all()) {
            String subject = identity.get(ExternalIdentityModel.SUBJECT);
            if (subject != null && !subject.isBlank()) {
                subjects.add(subject);
            }
        }
        return subjects;
    }

    /**
     * @param subjects the Proteus subjects another server named the person by, in this server's realm
     * @return the one account here those subjects are linked to; null for none, or for subjects linked to several
     */
    static @Nullable UUID accountOf(@NonNull List<String> subjects) {
        if (subjects.isEmpty()) {
            return null;
        }
        Set<UUID> accounts = new LinkedHashSet<>();
        for (Row identity : Models.get(ExternalIdentityModel.class).find().noCache()
                .where(ExternalIdentityModel.PROVIDER_SLUG.eq(ProteusSignIn.SLUG))
                .where(ExternalIdentityModel.SUBJECT.in(subjects))
                .only(ExternalIdentityModel.ID, ExternalIdentityModel.USER_ID).all()) {
            accounts.add(identity.get(ExternalIdentityModel.USER_ID));
        }
        return accounts.size() == 1 ? accounts.iterator().next() : null;
    }
}
