package be.elevenways.hohenheim.site;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.validator.Range;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The acts on a path protected with a signed link: replace its secret, end the old one, make one link; the panel's
 * Signed link tab and the assistant tools both run them.
 *
 * AIDEV-NOTE: a secret is never a result: the replace parks the new one for the caller's account (SignedLinkSecrets)
 * and answers words, so no command receipt, tool answer or history row holds it. A link is no stored secret, so the
 * make answers it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SignedLinks {

    /** The subject of every act here: one protected path. */
    public static final SubjectType<Row> PROTECTED_PATH = SubjectType.record(ProtectedPathModel.REF);

    /** The longest a made link works, in minutes: seven days, the furthest expiry the proxy accepts. */
    public static final int LONGEST_MINUTES = 10_080;

    /** How long a made link works when the call names no length. */
    public static final int DEFAULT_MINUTES = 30;

    private static final OperationCommand COMMAND = OperationCommand.perSubject();

    /** The path a link opens, under the protected path. */
    public static final StringField PATH = StringField.builder("path")
        .label(HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_path"))
        .help(HohenheimMicrocopy.HELP.of("signed_link_path_help"))
        .required()
        .build();

    /** How many minutes the link works. */
    public static final IntegerField MINUTES = IntegerField.builder().name("minutes")
        .defaultValue(DEFAULT_MINUTES)
        .validator(Range.of(1, LONGEST_MINUTES))
        .label(HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_minutes"))
        .help(HohenheimMicrocopy.HELP.of("signed_link_minutes_help"))
        .build();

    /**
     * What a link is made of, read once from the coerced form.
     *
     * @param path    the path it opens, as typed
     * @param minutes how long it works, null for {@link #DEFAULT_MINUTES}
     */
    public record MakeInput(@NonNull String path, @Nullable Integer minutes) {
    }

    /** Makes a new secret, parked for the caller; the old one keeps working for a while. */
    public static final Operation<Row, Void, Microcopy> REPLACE_SECRET =
            Operation.declare(HohenheimIds.id("replace_signed_link_secret"))
        .happened(OperationSentences.of("replace_signed_link_secret"))
        .label(HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_replace"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("replace_signed_link_secret_description"))
        .icon(Icon.of("rotate"))
        .one(PROTECTED_PATH)
        .gate(OperationGate.open())
        .result(Microcopy.class)
        .command(COMMAND)
        .register();

    /** Ends the old secret now: links signed with it stop opening at once. */
    public static final Operation<Row, Void, Microcopy> END_OLD_SECRET =
            Operation.declare(HohenheimIds.id("end_old_signed_link_secret"))
        .happened(OperationSentences.of("end_old_signed_link_secret"))
        .label(HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_end_old"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("end_old_signed_link_secret_description"))
        .icon(Icon.of("xmark"))
        .one(PROTECTED_PATH)
        .gate(OperationGate.open())
        .facts(OperationFact.DESTRUCTIVE)
        .result(Microcopy.class)
        .command(COMMAND)
        .register();

    /** Makes one link to a path under the protected path, answered as its address. */
    public static final Operation<Row, MakeInput, String> MAKE_LINK =
            Operation.declare(HohenheimIds.id("make_signed_link"))
        .happened(OperationSentences.of("make_signed_link"))
        .label(HohenheimMicrocopy.PROTECTED_PATH.of("signed_link_make"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("make_signed_link_description"))
        .icon(Icon.of("link"))
        .one(PROTECTED_PATH)
        .gate(OperationGate.open())
        .input(OperationInput.of(FormSpec.builder().add(PATH).add(MINUTES).build(), MakeInput.class,
            values -> new MakeInput(values.get(PATH), values.get(MINUTES))))
        .result(String.class)
        .command(COMMAND)
        .register();

    private SignedLinks() {
    }

    /** Loads the class, registering the operations; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }
}
