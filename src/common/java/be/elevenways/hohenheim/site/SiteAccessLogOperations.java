package be.elevenways.hohenheim.site;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StorageKeyed;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.traffic.common.StatusClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * The read of a site's access log: its lines over a time range, filtered by status, path and visitor, newest first,
 * one page at a time; the panel's Access log tab and an assistant's tool read through the same reader.
 *
 * AIDEV-NOTE: the log holds other people's data (addresses, user agents): its lines are answered to the reader of the
 * site and to nobody else, and never reach a history entry, a toast or an error. Who may read is the site's own
 * manage reach (SiteOperationHandlers.reaches), the rule of the tab too.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SiteAccessLogOperations {

    /** Which status class to read: one of the traffic totals' classes, or {@link #FAILED}. */
    public static final EnumField STATUS = statusField();

    /** The status filter's value for every refused and failed answer: the failed classes' keys joined, "4xx_5xx". */
    public static final String FAILED = failedToken();

    /** One exact status code to read. */
    public static final IntegerField STATUS_CODE = IntegerField.builder("status_code")
        .help(HohenheimMicrocopy.HELP.of("access_log_status_code"))
        .build();

    /** Only lines whose path holds this text. */
    public static final StringField PATH = StringField.builder("path")
        .help(HohenheimMicrocopy.HELP.of("access_log_path"))
        .build();

    /** Only lines of this visitor address, or of every address it starts when it ends in a dot or a colon. */
    public static final StringField VISITOR = StringField.builder("visitor")
        .help(HohenheimMicrocopy.HELP.of("access_log_visitor"))
        .build();

    /** The earliest moment to read. */
    public static final DateTimeField FROM = DateTimeField.builder("from")
        .help(HohenheimMicrocopy.HELP.of("access_log_from"))
        .build();

    /** The latest moment to read. */
    public static final DateTimeField TO = DateTimeField.builder("to")
        .help(HohenheimMicrocopy.HELP.of("access_log_to"))
        .build();

    /** The lines one page holds, the tab's and the tool's. */
    public static final int PAGE_LINES = 50;

    /** Which page of the lines to read, newest first. */
    public static final IntegerField PAGE = IntegerField.builder("page")
        .help(HohenheimMicrocopy.HELP.of("access_log_page").withArg("lines", PAGE_LINES))
        .build();

    /**
     * The read's input; every filter null reads every line.
     *
     * @param status      the status class's token
     * @param status_code one exact status
     * @param path        text the path holds
     * @param visitor     the visitor address, a prefix when it ends in a dot or a colon
     * @param from        the earliest moment
     * @param to          the latest moment
     * @param page        the page, 1 for the newest
     */
    public record ReadInput(@Nullable String status, @Nullable Integer status_code, @Nullable String path,
                            @Nullable String visitor, @Nullable Instant from, @Nullable Instant to,
                            @Nullable Integer page) {
    }

    /**
     * One page of a site's lines, newest first.
     *
     * @param lines      the lines as the log holds them
     * @param first      the position of the first line among every match, 1 for the newest
     * @param matched    how many lines matched in what was read
     * @param complete   whether every line of the range was read; false when the read stopped at its bound
     * @param readBackTo how far back an incomplete read came, null when complete
     */
    public record Lines(@NonNull List<String> lines, int first, long matched, boolean complete,
                        @Nullable Instant readBackTo) {
    }

    /** Reads one page of the site's access log, newest first. */
    public static final Operation<Row, ReadInput, Lines> READ =
        Operation.declare(HohenheimIds.id("read_site_access_log"))
            .happened(OperationSentences.of("read_site_access_log"))
            .label(HohenheimMicrocopy.ACCESS_LOG.of("access_log_read"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("read_site_access_log_description"))
            .icon(Icon.of("list-ul"))
            .one(SiteOperations.SITE)
            .verb(HohenheimVerbs.READ_LOG)
            // Who may read is the server-attached authorizer: the site's manage reach (SiteOperationHandlers).
            .gate(OperationGate.open())
            .input(OperationInput.of(FormSpec.builder().add(STATUS).add(STATUS_CODE).add(PATH).add(VISITOR)
                    .add(FROM).add(TO).add(PAGE).build(), ReadInput.class,
                values -> new ReadInput(values.get(STATUS), values.get(STATUS_CODE),
                    Texts.trimmedOrNull(values.get(PATH)), Texts.trimmedOrNull(values.get(VISITOR)),
                    values.get(FROM), values.get(TO), values.get(PAGE))))
            .result(Lines.class)
            .facts(OperationFact.READ_ONLY)
            .register();

    private SiteAccessLogOperations() {
    }

    /** @return whether an answer of the class was refused or failed, the lines {@link #FAILED} reads */
    public static boolean failed(@NonNull StatusClass statusClass) {
        return switch (statusClass) {
            case CLIENT_ERROR, SERVER_ERROR -> true;
            case INFORMATIONAL, SUCCESS, REDIRECTION, OTHER -> false;
        };
    }

    /**
     * @param token a value of {@link #STATUS}
     * @return the statuses the value reads; an unknown value reads none (fail closed)
     */
    public static @NonNull IntPredicate statusesOf(@NonNull String token) {
        if (FAILED.equals(token)) {
            return status -> failed(StatusClass.of(status));
        }
        StatusClass named = StorageKeyed.findOrNull(List.of(StatusClass.values()), token);
        return named == null ? status -> false : status -> StatusClass.of(status) == named;
    }

    /** @return the status field carrying every class and then {@link #FAILED}, so no list of them is spelled twice */
    private static @NonNull EnumField statusField() {
        EnumField.Builder builder = EnumField.builder("status").help(HohenheimMicrocopy.HELP.of("access_log_status"));
        for (StatusClass each : StatusClass.values()) {
            builder.value(each.storageKey(), value -> value.displayName(each.storageKey()).label(each.label()));
        }
        String failed = failedToken();
        builder.value(failed, value -> value.displayName(failed)
            .label(HohenheimMicrocopy.ACCESS_LOG.of("access_log_status_" + failed)));
        return builder.build();
    }

    private static @NonNull String failedToken() {
        List<String> keys = new ArrayList<>();
        for (StatusClass each : StatusClass.values()) {
            if (failed(each)) keys.add(each.storageKey());
        }
        return String.join("_", keys);
    }
}
