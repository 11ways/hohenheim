package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The plumbing the API journey tests share: body encoding, response field reads, and a user row. */
final class ApiSupport {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FIELD = Pattern.compile("\"field\"\\s*:\\s*\"([^\"]+)\"");

    private ApiSupport() {
    }

    /** A urlencoded body from alternating key/value pairs. */
    static String form(String... pairs) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    /** The id the response carries. */
    static int idOf(String json) {
        Matcher matcher = ID.matcher(json);
        assertThat(matcher.find()).as("the response carries an id: " + json).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    /** The refusal code the response carries. */
    static String codeOf(String json) {
        Matcher matcher = CODE.matcher(json);
        assertThat(matcher.find()).as("the refusal carries a code: " + json).isTrue();
        return matcher.group(1);
    }

    /** The path of the value the refusal names, or null when it names none. */
    static String fieldOf(String json) {
        Matcher matcher = FIELD.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** An enabled user row; returns its id. */
    static int user(String email, String name) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, name);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }
}
