package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.model.SessionModel;
import be.elevenways.hohenheim.model.UserModel;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.http.HttpConduit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Authentication utilities: password hashing, session management, cookie parsing.
 */
public class AuthHelper {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String COOKIE_NAME = "hh_session";

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String saltBase64 = Base64.getEncoder().encodeToString(salt);
        String hash = sha256(saltBase64 + password);
        return saltBase64 + ":" + hash;
    }

    public static boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || !storedHash.contains(":")) return false;
        String[] parts = storedHash.split(":", 2);
        String salt = parts[0];
        String expectedHash = parts[1];
        return sha256(salt + password).equals(expectedHash);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String createSession(int userId) {
        var ds = HohenheimDatabase.datasource();
        var sessionModel = new SessionModel(ds);

        String token = UUID.randomUUID().toString();
        Row session = sessionModel.createEmptyRow();
        session.set(SessionModel.TOKEN, token);
        session.set(SessionModel.USER_ID, userId);
        session.set(SessionModel.EXPIRES_AT, Instant.now().plus(30, ChronoUnit.DAYS));
        sessionModel.save(session);
        return token;
    }

    public static void deleteSession(String token) {
        if (token == null) return;
        var ds = HohenheimDatabase.datasource();
        var sessionModel = new SessionModel(ds);
        sessionModel.find().where(SessionModel.TOKEN.eq(token)).delete();
    }

    public static Row getCurrentUser(Conduit conduit) {
        String token = getCookieValue(conduit, COOKIE_NAME);
        if (token == null) return null;

        var ds = HohenheimDatabase.datasource();
        var sessionModel = new SessionModel(ds);
        var userModel = new UserModel(ds);

        Row session = sessionModel.find().where(SessionModel.TOKEN.eq(token)).first();
        if (session == null) return null;

        Object expiresAt = session.get(SessionModel.EXPIRES_AT);
        if (expiresAt instanceof Instant && ((Instant) expiresAt).isBefore(Instant.now())) {
            sessionModel.find().where(SessionModel.TOKEN.eq(token)).delete();
            return null;
        }

        Object userIdRaw = session.get(SessionModel.USER_ID);
        if (userIdRaw == null) return null;
        int userId = ((Number) userIdRaw).intValue();
        return userModel.findById(userId);
    }

    public static boolean hasAnyUsers() {
        var ds = HohenheimDatabase.datasource();
        var userModel = new UserModel(ds);
        return userModel.find().count() > 0;
    }

    public static Row createUser(String email, String name, String password) {
        var ds = HohenheimDatabase.datasource();
        var userModel = new UserModel(ds);

        Row user = userModel.createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.NAME, name);
        user.set(UserModel.PASSWORD_HASH, hashPassword(password));
        userModel.save(user);
        return user;
    }

    public static String getCookieValue(Conduit conduit, String name) {
        if (!(conduit instanceof HttpConduit httpConduit)) return null;

        List<String> cookieHeaders = httpConduit.getRequestHeaders("Cookie");
        if (cookieHeaders == null) return null;

        for (String header : cookieHeaders) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(name + "=")) {
                    return trimmed.substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    public static void setSessionCookie(Conduit conduit, String token) {
        if (!(conduit instanceof HttpConduit httpConduit)) return;
        httpConduit.addResponseHeader("Set-Cookie",
            COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000");
    }

    public static void clearSessionCookie(Conduit conduit) {
        if (!(conduit instanceof HttpConduit httpConduit)) return;
        httpConduit.addResponseHeader("Set-Cookie",
            COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
    }
}
