package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.model.RedirectStatuses;
import be.elevenways.hohenheim.server.proxy.RedirectLocation;
import be.elevenways.hohenheim.server.proxy.RequestPath;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import io.undertow.util.Headers;

import java.util.List;
import java.util.Map;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Sends an HTTP redirect to a target URL.
 */
public class RedirectUpstreamKind implements UpstreamKindHandler {

    public static final Identifier ID = HohenheimIds.id("redirect");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField TARGET_URL = SETTINGS_SCHEMA.addField(
        StringField.builder().name("target_url").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("target_url"))
            .help(HohenheimMicrocopy.HELP.of("target_url")).build());

    public static final EnumField HTTP_STATUS = SETTINGS_SCHEMA.addField(
        RedirectStatuses.field("http_status").build());

    public static final BooleanField PRESERVE_PATH = SETTINGS_SCHEMA.addField(
        BooleanField.builder("preserve_path").defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("preserve_path"))
            .help(HohenheimMicrocopy.HELP.of("preserve_path")).build());

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(UpstreamSettings.delay());

    // The target, the status and whether the path is kept are the decision; the delay is
    // right almost always, so its Connection section folds. AIDEV-NOTE: never the framework's
    // generic "Advanced" here (the site form has its own); after the fields -- membership is
    // validated eagerly.
    static {
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.FORWARDING,
            List.of(DELAY.getName())));
    }

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Redirect"; }

    @Override
    public Icon getIcon() { return Icon.of("up-right-from-square"); }

    @Override
    public BadgeColor color() { return ColorHue.CYAN; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    /** The redirect target. */
    @Override
    public String upstreamSummary(Map<String, Object> settings) {
        Object target = settings.get(TARGET_URL.getName());
        return target != null && !String.valueOf(target).isBlank() ? String.valueOf(target) : null;
    }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        String targetUrl = (String) settings.get("target_url");
        int status = RedirectStatuses.codeOf(settings.get(HTTP_STATUS.getName()));
        boolean preservePath = Boolean.TRUE.equals(settings.get(PRESERVE_PATH.getName()));

        if (targetUrl == null || targetUrl.isEmpty()) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("No redirect target configured");
            };
        }
        // Validate the target to prevent an open redirect or script URL (RedirectStatuses.isTarget).
        if (!RedirectStatuses.isTarget(targetUrl)) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("Invalid redirect target: must be an http:// or https:// address, or a path on this site");
            };
        }

        // The RAW path (strip_path already applied): the decoded one would turn an encoded %0a or %3F back into live
        // syntax inside the Location header.
        return (exchange, forwarder) -> RedirectLocation.send(exchange, status, targetUrl,
            preservePath ? RequestPath.rawPathOf(exchange) : null, preservePath);
    }
}
