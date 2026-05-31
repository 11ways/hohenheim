package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.NodeVersionModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.zenit.common.orm.model.Models;

/**
 * Registers the model singletons for tests that don't boot the full runtime (the MODELS stage's
 * ClassGraph discovery doesn't run). Idempotent. Tests still scope the datasource via {@code Db.run}.
 */
public final class TestModels {

    private TestModels() {
    }

    public static void registerAll() {
        Models.registerInstance(new SiteModel());
        Models.registerInstance(new SiteDomainModel());
        Models.registerInstance(new CertificateModel());
        Models.registerInstance(new AccessListModel());
        Models.registerInstance(new AuditLogModel());
        Models.registerInstance(new NodeVersionModel());
        Models.registerInstance(new SystemUserModel());
        Models.registerInstance(new ProclogModel());
        Models.registerInstance(new DatabaseModel());
        Models.registerInstance(new ServerModel());
        Models.registerInstance(new NotificationChannelModel());
    }
}
