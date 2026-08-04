package be.elevenways.hohenheim.server.preview;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.orm.GeneratedRows;

/**
 * The attribution invariant on generated site-domain rows (the GeneratedDnsRecords
 * shape): inside the system scope the four columns are DERIVED; outside it an
 * attributed row is read-only and a write that would move attribution is refused.
 * This is what makes a preview's generated hostname SELF-SCOPED -- the sweep removes
 * rows by exact attribution, and a hand-authored row with the same hostname carries
 * none, so it is never adopted or deleted.
 */
public final class PreviewDomains {

    /** {@code generated_by} token and Accountability origin of preview writes. */
    public static final String SOURCE = "preview";

    private static volatile boolean installed;

    private PreviewDomains() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        GeneratedRows.installGuards(SiteDomainModel.SCHEMA, SiteDomainModel.class,
            new GeneratedRows.Columns(SiteDomainModel.GENERATED_BY,
                SiteDomainModel.GENERATED_FOR_MODEL, SiteDomainModel.GENERATED_FOR_ID,
                SiteDomainModel.GENERATED_AT),
            "domain_generated_readonly", "domain_generated_attribution");
    }
}
