package be.elevenways.hohenheim.site;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.coerce.PrimitiveCoercion;
import be.elevenways.zenit.common.edit.Array;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.ListField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.forms.common.edit.ItemList;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Protect one path of a site in place: the path, how a visitor proves who they are, and who may pass, in ONE operation
 * whose handler writes a dedicated access list, its rules and the protected path together.
 *
 * AIDEV-NOTE: the dedicated list is never shared (a shared policy stays the access-list screens' job), and an empty
 * answer is refused by the protected-path invariant every writer passes, not by a second check here.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ProtectPath {

    /** A visitor proves a username and password (HTTP basic credentials). */
    public static final String METHOD_PASSWORD = "password";

    /** A visitor comes from one of the listed networks. */
    public static final String METHOD_NETWORK = "network";

    /** A visitor signs in through one of the installation's sign-in providers. */
    public static final String METHOD_SIGN_IN = "sign_in";

    public static final StringField PATH = StringField.builder("path")
        .label(copy("path"))
        .help(copy("path_help"))
        .placeholder("/wp-admin")
        .required()
        .build();

    public static final EnumField METHOD = EnumField.builder("method")
        .value(METHOD_PASSWORD, v -> v.displayName("Password").label(copy("method_password")).icon("key"))
        .value(METHOD_NETWORK, v -> v.displayName("Network").label(copy("method_network")).icon("network-wired"))
        .value(METHOD_SIGN_IN, v -> v.displayName("Sign-in").label(copy("method_sign_in")).icon("user-lock"))
        .defaultValue(METHOD_PASSWORD)
        .label(copy("method"))
        .required()
        .build();

    static final StringField USERNAME = StringField.builder("username").label(copy("username")).build();

    static final StringField PASSWORD = StringField.builder().name("password").secret().label(copy("password")).build();

    /** The people who may pass a password-protected path, one username and password each. */
    public static final ItemList PEOPLE = ItemList.of("people", FormSpec.builder()
        .add(USERNAME)
        .add(PASSWORD)
        .build());

    private static final StringField NETWORK = StringField.builder("network").placeholder("203.0.113.0/24").build();

    public static final ListField<String> NETWORKS = ListField.<String>builder(NETWORK)
        .name("networks")
        .label(copy("networks"))
        .help(copy("networks_help"))
        .build();

    public static final IntegerField PROVIDER_ID = IntegerField.builder().name("provider_id")
        .label(copy("provider"))
        .build();

    private static final FormSpec FORM = FormSpec.builder()
        .add(PATH)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(METHOD))
        .add(PEOPLE)
        .add(Array.of(NETWORKS, NETWORK).build())
        .add(PROVIDER_ID)
        .showWhen(PEOPLE.name(), METHOD.getName(), METHOD_PASSWORD)
        .showWhen(NETWORKS.getName(), METHOD.getName(), METHOD_NETWORK)
        .showWhen(PROVIDER_ID.getName(), METHOD.getName(), METHOD_SIGN_IN)
        .build();

    /**
     * What the operator asked for, read once from the coerced form.
     *
     * @param path        the path prefix to protect
     * @param method      one of the METHOD_* tokens
     * @param people      the username/password pairs for {@link #METHOD_PASSWORD}
     * @param networks    the networks for {@link #METHOD_NETWORK}
     * @param provider_id the sign-in provider for {@link #METHOD_SIGN_IN}
     */
    @SuppressWarnings("checkstyle:RecordComponentName")
    public record Input(@NonNull String path, @NonNull String method, @NonNull List<Map<String, Object>> people,
                        @NonNull List<String> networks, @Nullable Integer provider_id) {
    }

    public static final OperationInput<Input> INPUT = OperationInput.of(FORM, Input.class, values -> new Input(
        values.get(PATH), values.get(METHOD), people(values.get(PEOPLE.name())),
        PrimitiveCoercion.toTrimmedTextList(values.get(NETWORKS)), values.get(PROVIDER_ID)));

    /** The site record's "Protect a path", answered with the protected path's id. */
    public static final Operation<Row, Input, Integer> OPERATION = Operation.declare(HohenheimIds.id("protect_path"))
        .label(copy("action"))
        .description(copy("description"))
        .happened(Microcopy.of("happened").withFilter("scope", "protect_path"))
        .icon(Icon.of("lock"))
        .one(SiteOperations.SITE)
        .gate(OperationGate.open())
        .input(INPUT)
        .result(Integer.class)
        .command(OperationCommand.perSubject())
        .register();

    private ProtectPath() {
    }

    @SuppressWarnings("unchecked")
    private static @NonNull List<Map<String, Object>> people(@Nullable Object value) {
        List<Map<String, Object>> people = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    people.add((Map<String, Object>) map);
                }
            }
        }
        return people;
    }

    private static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "protect_path");
    }
}
