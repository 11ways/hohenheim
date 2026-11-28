package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.StoredDocumentValues;
import be.elevenways.zenit.common.orm.model.ModelChanges;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.peer.SharedRow;
import be.elevenways.zenit.server.peer.SharedStates;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The state kind of certificates: what this server tells its master of each of its certificates, which are no
 * shared record (their keys never leave the server), as the facts its own Certificates list draws.
 *
 * AIDEV-NOTE: an entry is the certificate's portable document of the columns its list reads
 * ({@link CertificateParts#toldColumns}: never a chain, key or signing request), keyed by its id, with the facts of
 * this process beside them: its state line (whether the running proxy loaded it) and its verdict. The master revives
 * it as a certificate row (PeerRecords.rows), so its own list code draws it. A certificate write is no shared-model
 * commit: its committed writes tell the master at once here ({@link SharedStates#changed}), as do the sites' and
 * addresses' state, whose HTTPS lines read the certificates; the proxy loading one later is found at the minute
 * compare.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class CertificateStates {

    /** The kind a certificate's state is offered and kept under. */
    public static final Identifier KIND = HohenheimIds.id("certificate_state");

    private static final AtomicBoolean DECLARED = new AtomicBoolean();

    private CertificateStates() {
    }

    /** Offers this server's certificates' state to its master, once however often the handlers install. */
    public static void declare() {
        if (DECLARED.compareAndSet(false, true)) {
            SharedStates.declare(KIND, CertificateStates.class, CertificateStates::entries);
            CertificateModel certificates = Models.get(CertificateModel.class);
            ModelChanges.listenRows(certificates, rows -> {
                Datasource datasource = certificates.getResolvedDatasource();
                SharedStates.changed(KIND, datasource);
                SharedStates.changed(SiteStates.SITE_KIND, datasource);
                SharedStates.changed(SiteStates.ADDRESS_KIND, datasource);
            });
        }
    }

    /** @return one entry per listed certificate (never the ACME account's row), its name as the title */
    private static @NonNull List<SharedRow> entries() {
        CertificateModel model = Models.get(CertificateModel.class);
        Datasource datasource = model.getResolvedDatasource();
        List<SharedRow> entries = new ArrayList<>();
        for (Row cert : model.find().noCache().where(HohenheimSources.notTheAcmeAccountRow()).all()) {
            Map<String, Object> portable = StoredDocumentValues.portableDocument(model, cert, datasource);
            Map<String, Object> told = new LinkedHashMap<>();
            for (String column : CertificateParts.toldColumns()) {
                told.put(column, portable.get(column));
            }
            told.put("state", ToldFacts.line(CertificateParts.stateCell(cert)));
            told.put("health", ToldFacts.health(CertificateParts.health(cert)));
            entries.add(new SharedRow(String.valueOf((Object) cert.get(CertificateModel.ID)), null,
                String.valueOf((Object) cert.get(CertificateModel.NICE_NAME)), Zenit.DRY.toJson(told)));
        }
        return entries;
    }
}
