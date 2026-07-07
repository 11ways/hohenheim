package be.elevenways.hohenheim;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.NodeVersionModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SiteSessionModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;

import java.util.List;

/**
 * Explicit registration of the Hohenheim model singletons. Common code because
 * the browser bundle needs the models too (record sources resolve them
 * client-side); the server boot scan and tests reach the same idempotent
 * registration.
 */
public final class HohenheimModels {

    private static final List<Model> ALL = List.of(
        new SiteModel(),
        new SiteDomainModel(),
        new CertificateModel(),
        new AccessListModel(),
        new SiteAuthProviderModel(),
        new DatabaseModel(),
        new ServerModel(),
        new NotificationChannelModel(),
        new AuditLogModel(),
        new ProclogModel(),
        new NodeVersionModel(),
        new SystemUserModel(),
        new SiteSessionModel()
    );

    private HohenheimModels() {
    }

    /**
     * Register every Hohenheim model singleton. Idempotent.
     */
    public static void registerAll() {
        for (Model model : ALL) {
            Models.registerInstance(model);
        }
    }
}
