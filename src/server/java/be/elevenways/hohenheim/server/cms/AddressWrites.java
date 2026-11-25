package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;

import java.util.List;

/**
 * A site's address created or changed by its settings as an assistant names them, through the address panel
 * resource's own create and save, so the route claims, the quarantine, the passthrough refusals and the tenant freeze
 * decide as they do for the panel's form.
 *
 * AIDEV-NOTE: the entries are the address form's own fields (DomainParts.adminFormSpec), the site and the pinned
 * certificate taken by their codes. Someone who is no administrator writes through the /manage twin, whose form takes
 * fewer fields: one it does not take is refused there, and TenantWrites freezes the rest on every writer anyway.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AddressWrites {

    /** The argument naming the site an address belongs to. */
    public static final String SITE = "site";

    /** The argument pinning the certificate an address serves. */
    public static final String CERTIFICATE = "certificate";

    /** The address's own settings, as its form names them. */
    private static final List<Field<?, ?>> SETTINGS = List.of(SiteDomainModel.HOSTNAME, SiteDomainModel.MATCH_TYPE,
        SiteDomainModel.PATH, SiteDomainModel.STRIP_PATH, SiteDomainModel.LISTEN_ON, SiteDomainModel.FORCE_SSL,
        SiteDomainModel.HSTS_ENABLED, SiteDomainModel.HSTS_SUBDOMAINS, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT,
        SiteDomainModel.CUSTOM_HEADERS, SiteDomainModel.RESPONSE_HEADERS);

    private static final PanelRecordWrites WRITES = PanelRecordWrites.of("address", SiteDomainModel.MODEL_ID,
            CertificateOperations.DOMAIN, SiteDomainModel.ID, HohenheimSlugs.DOMAINS)
        .link(SITE, HohenheimMicrocopy.HELP.of("address_site"), SiteDomainModel.SITE_ID, SiteModel.MODEL_ID, true)
        .settings(SETTINGS)
        .link(CERTIFICATE, HohenheimMicrocopy.HELP.of("address_certificate"), SiteDomainModel.CERTIFICATE_ID,
            CertificateModel.MODEL_ID, false)
        .build();

    /** Adds an address to a site. */
    public static final Operation<Void, OperationInput.Values, Integer> CREATE = WRITES.create();

    /** Changes what the call sends of an address; every other setting stays as it is. */
    public static final Operation<Row, OperationInput.Values, String> CHANGE = WRITES.change();

    /** Removes an address from its site through the panel's own delete. */
    public static final Operation<Row, Void, Integer> DELETE = PanelRecordWrites.deletion("address",
        CertificateOperations.DOMAIN, HohenheimSlugs.DOMAINS);

    private AddressWrites() {
    }

    /** Installs the handlers; idempotent through the class initializer. */
    public static void init() {
    }
}
