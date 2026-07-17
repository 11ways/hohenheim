package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsSecMaterial;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import org.xbill.DNS.DSRecord;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Zone-file tab on a DNS zone: standard master-file export plus a paste-to-
 * import form (the POST is the host-declared DNS_ZONE_IMPORT endpoint).
 */
public final class DnsZoneFilePage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dns_zone_file"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("zone_file").withFilter("scope", "dns_zone"); }
    @Override public @NonNull String slug() { return "zonefile"; }
    @Override public @NonNull Icon icon() { return Icon.of("file-lines"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row zone) {
        Map<String, Object> vars = new HashMap<>();
        String origin = zone.get(DnsZoneModel.ORIGIN);
        vars.put("title", origin + " - Zone file");
        vars.put("zoneId", zone.get(DnsZoneModel.ID));
        vars.put("origin", origin);
        vars.put("zoneText", DnsZoneFiles.export(zone));

        // DNSSEC: the DS record the operator lodges with the registrar.
        DSRecord ds = Boolean.TRUE.equals(zone.get(DnsZoneModel.DNSSEC_ENABLED))
            ? DnsSecMaterial.dsRecord(zone) : null;
        vars.put("dsRecord", ds != null ? ds.toString() : "");
        String error = conduit.getQueryParam("error");
        vars.put("error", error != null ? error : "");
        String imported = conduit.getQueryParam("imported");
        vars.put("imported", imported != null ? imported : "");
        String skipped = conduit.getQueryParam("skipped");
        vars.put("skipped", skipped != null ? skipped : "");
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/dns-zone-file"), vars);
    }
}
