package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;

/**
 * The linked servers' attention items: one per linked server that does not answer or refuses this server past the
 * outage grace (the moment its alert was sent), is another server than the one that joined, or runs another version.
 *
 * AIDEV-NOTE: role-free like the link itself; it reads the state the probe stored and calls nobody.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ClusterAttention {

    private ClusterAttention() {
    }

    /** Adds one item per linked server whose state needs attention. */
    public static void linkedServers(@NonNull List<AttentionItem> items) {
        String version = LinkedServers.ownVersion();
        for (Row row : LinkedServers.all()) {
            LinkState state = LinkState.of(row, version);
            if (!state.needsAttention()) {
                continue;
            }
            if (row.get(LinkedServerModel.FAILING_SINCE) != null && row.get(LinkedServerModel.ALERTED_AT) == null) {
                // Inside the grace the Servers page says it; the item comes with the alert.
                continue;
            }
            LinkFailure failure = LinkFailure.byToken(row.get(LinkedServerModel.LAST_FAILURE));
            items.add(item(state == LinkState.OTHER_SERVER ? AttentionSeverity.ERROR : AttentionSeverity.WARNING,
                "server",
                HohenheimMicrocopy.ATTENTION_TITLE.of("linked_server_" + state.token())
                    .withArg("name", String.valueOf((Object) row.get(LinkedServerModel.NAME)))
                    .withArg("since", Objects.requireNonNullElse(row.get(LinkedServerModel.FAILING_SINCE),
                        Instant.EPOCH)),
                failure == null ? null : failure.label(),
                CmsRoutes.list(ADMIN, HohenheimSlugs.LINKED_SERVERS),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_linked_servers")));
        }
    }
}
