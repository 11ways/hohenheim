package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.security.SshAuthWatcher;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

import static be.elevenways.hohenheim.server.cms.AttentionItems.copy;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;
import static be.elevenways.hohenheim.server.cms.AttentionItems.literal;

/**
 * The FIREWALL role's attention items: the managed Spamservice and the SSH watcher, each judged
 * on its own in-memory snapshot.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class FirewallAttention {

    private static final String ADMIN = HohenheimSlugs.ADMIN;

    private FirewallAttention() {
    }

    /** Surfaces an enabled managed Spamservice that is not currently ready. */
    static void spamserviceIssue(List<AttentionItem> items) {
        AttentionItem issue = spamserviceIssue(SpamserviceManager.get().snapshot());
        if (issue != null) {
            items.add(issue);
        }
    }

    /**
     * The decision behind the spamservice item, on a snapshot so it is testable.
     * An unconfigured or deliberately disabled service is a CHOICE, never a warning;
     * only an enabled one that is not ready gets an item, and that item explains
     * itself (the last error when there is one, else a localized state sentence)
     * and links to the settings mount where the service is administered.
     */
    static @Nullable AttentionItem spamserviceIssue(SpamserviceManager.Snapshot snapshot) {
        if (!snapshot.needsAttention()) {
            return null;
        }
        Microcopy detail = snapshot.lastError() != null
            ? literal(snapshot.lastError())
            : copy("spamservice_not_ready", "attention_detail", "state", snapshot.state());
        return item(AttentionSeverity.WARNING, "shield",
            copy("not_ready", "spamservice"), detail,
            CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG));
    }

    /**
     * The SSH watcher's own health, on a snapshot so it is testable.
     *
     * AIDEV-NOTE: a watcher that cannot read the journal bans NOBODY while every surface
     * still says SSH watching is on -- the silent-success shape. It reports itself here
     * (and once in the log) instead. An install that never asked for it is a CHOICE and
     * gets no row, exactly like the spamservice item beside it.
     */
    public static @Nullable AttentionItem sshWatchIssue(SshAuthWatcher.Snapshot snapshot) {
        if (!snapshot.configured()) {
            return null;
        }
        if (snapshot.running() && snapshot.lastError() == null) {
            return null;
        }
        Microcopy detail = snapshot.lastError() != null
            ? literal(snapshot.lastError())
            : copy("ssh_watch", "attention_detail");
        return item(AttentionSeverity.WARNING, "shield",
            copy("ssh_watch", "attention_title"), detail,
            CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG));
    }
}
