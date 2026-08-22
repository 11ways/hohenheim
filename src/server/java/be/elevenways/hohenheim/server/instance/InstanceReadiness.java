package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * WHEN a freshly started workload counts as ready, per its template's
 * {@link ReadinessKind} -- the enforcement half of a vocabulary that until now only
 * existed as a stored column.
 *
 * AIDEV-NOTE: two of the three kinds live here and one does not, and that split is
 * deliberate rather than an omission. {@code console_line} needs the console attached
 * BETWEEN create and start (a line printed in the first instant must not be missed), which
 * is why {@code InstanceConsoles} owns it and stamps {@code starting} until the line
 * appears. {@code port} and {@code http} can only be answered after the workload is up and
 * its host port is known, so they are a blocking probe at the end of the deploy. Putting
 * all three in one place would mean either attaching a console to every workload or
 * probing before the port exists.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceReadiness {

    /** How long a port or http probe waits before the deploy is refused. */
    static final long WINDOW_MS = 90 * 1000;

    private static final long INTERVAL_MS = 500;

    private InstanceReadiness() {
    }

    /**
     * The readiness kind this record's template declares.
     *
     * @return null when the record has no template, or when the template's stored value is
     *         not a member -- unknown fails CLOSED to "nothing to wait for" rather than to
     *         a guess about which probe was meant
     */
    public static @Nullable ReadinessKind declaredKind(@NonNull Row instance) {
        Integer templateId = instance.get(InstanceModel.TEMPLATE_ID);
        if (templateId == null) {
            return null;
        }
        Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
        return template == null
            ? null : ReadinessKind.forToken(template.get(InstanceTemplateModel.READINESS_KIND));
    }

    /** The template's {@code readiness_target}: an http path, or blank. */
    static @NonNull String declaredTarget(@NonNull Row instance) {
        Integer templateId = instance.get(InstanceModel.TEMPLATE_ID);
        Row template = templateId == null
            ? null : Models.get(InstanceTemplateModel.class).findById(templateId);
        String target = template == null
            ? null : template.get(InstanceTemplateModel.READINESS_TARGET);
        return target == null ? "" : target.trim();
    }

    /**
     * Block until the workload answers its declared readiness probe.
     *
     * @throws Violations {@code readiness_timed_out} -- a workload that never answered must
     *         not be stamped running, because the surface would then say "up" about
     *         something nothing has ever reached
     */
    public static void await(@NonNull Row instance, @NonNull InstanceStatus status) {

        ReadinessKind kind = declaredKind(instance);

        if (kind == null || kind == ReadinessKind.CONSOLE_LINE) {
            // console_line is the console hub's; see the class note.
            return;
        }

        Integer port = publishedPortOf(status);

        if (port == null) {
            // AIDEV-NOTE: the two kinds differ HERE, and deliberately. A workload that
            // publishes no port has nothing for a PORT probe to connect to, and refusing
            // would gate every templated workload that simply is not a service (the stored
            // column's default is `port`, so "declared" and "meant it" cannot be told
            // apart) -- it is logged instead. HTTP is different: it names a PATH the
            // operator wrote down, and there is no honest way to answer it without a port,
            // so it refuses rather than reporting a probe it never ran.
            if (kind == ReadinessKind.HTTP) {
                throw Violations.ofForm(refusal("readiness_needs_port", instance, kind, ""));
            }
            Blast.log("READINESS:", instance.get(InstanceModel.NAME),
                "declares port readiness but publishes no port; nothing to wait for");
            return;
        }

        switch (kind) {
            case PORT -> awaitPort(instance, port);
            case HTTP -> ReleaseEngine.probe(port, declaredTarget(instance));
            case CONSOLE_LINE -> { }
        }
    }

    /** Wait for a TCP connect to succeed on the published host port. */
    private static void awaitPort(@NonNull Row instance, int port) {

        long deadline = System.currentTimeMillis() + WINDOW_MS;
        String lastReason = "";

        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                return;
            } catch (IOException refused) {
                lastReason = String.valueOf(refused.getMessage());
            }
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Blast.log("READINESS: port", port, "never opened -", lastReason);
        throw Violations.ofForm(refusal("readiness_timed_out", instance, ReadinessKind.PORT,
            lastReason));
    }

    private static @Nullable Integer publishedPortOf(@NonNull InstanceStatus status) {
        return status.publishedPort();
    }

    private static @NonNull Microcopy refusal(@NonNull String key, @NonNull Row instance,
                                              @NonNull ReadinessKind kind,
                                              @NonNull String reason) {
        return Microcopy.of(key).withFilter("scope", "violations")
            .withArg("name", String.valueOf((Object) instance.get(InstanceModel.NAME)))
            .withArg("kind", kind.label())
            .withArg("reason", reason);
    }
}
