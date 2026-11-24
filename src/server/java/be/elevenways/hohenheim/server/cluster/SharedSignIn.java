package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.server.auth.ProteusSignIn;
import be.elevenways.zenit.auth.model.ExternalIdentityModel;
import be.elevenways.zenit.auth.server.identity.IdentityLoginService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one way a master and a server it manages name the same person: by the Proteus sign-in both offer, as the
 * realm ({@link ProteusSignIn#realm}) and that realm's subjects.
 *
 * AIDEV-NOTE: never by an e-mail address and never by a provider slug: only an identity link that realm vouched
 * for counts, so a link made under another realm, or not signed in through since realms are recorded, names
 * nobody. One person may hold several subjects (accounts Proteus merged); a set of subjects naming more than one
 * account here names nobody.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class SharedSignIn {

    private SharedSignIn() {
    }

    /** @return the subjects the realm vouched for on the account here, empty for none */
    static @NonNull List<String> subjectsOf(@NonNull UUID account, @NonNull String realm) {
        return IdentityLoginService.vouchedSubjects(account, realm);
    }

    /**
     * @param subjects the subjects another server named the person by
     * @param realm    this server's realm, which that server answers too
     * @return the one account here the realm vouched those subjects for; null for none, or for several
     */
    static @Nullable UUID accountOf(@NonNull List<String> subjects, @NonNull String realm) {
        if (subjects.isEmpty()) {
            return null;
        }
        Set<UUID> accounts = new LinkedHashSet<>();
        for (Row identity : Models.get(ExternalIdentityModel.class).find().noCache()
                .where(ExternalIdentityModel.REALM.eq(realm))
                .where(ExternalIdentityModel.SUBJECT.in(subjects))
                .only(ExternalIdentityModel.ID, ExternalIdentityModel.USER_ID).all()) {
            accounts.add(identity.get(ExternalIdentityModel.USER_ID));
        }
        return accounts.size() == 1 ? accounts.iterator().next() : null;
    }
}
