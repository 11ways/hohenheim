package be.elevenways.hohenheim;

/**
 * The declaring home of the panel slugs and of the resource slugs that common code must also spell.
 *
 * AIDEV-NOTE: a resource declares its slug as its own {@code SLUG} constant; when common code
 * (endpoint paths, sources) needs the same slug it lives HERE and the resource's constant aliases it,
 * so the URL segment and the peer slug cannot drift apart.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class HohenheimSlugs {

    /** The operator panel, served at /admin. */
    public static final String ADMIN = "admin";

    /** The delegated tenant panel, served at /manage. */
    public static final String MANAGE = "manage";

    public static final String ACCESS_LISTS = "access-lists";
    public static final String CERTIFICATES = "certificates";
    public static final String CERTIFICATES_REQUEST = "certificates-request";
    public static final String DNS_ZONES = "dns-zones";
    public static final String GIT_PROVIDERS = "git-providers";
    public static final String INSTANCE_TEMPLATES = "instance-templates";
    public static final String INSTANCE_TEMPLATES_IMPORT = "instance-templates-import";
    public static final String INSTANCES = "instances";
    public static final String SITES = "sites";

    private HohenheimSlugs() {
    }
}
