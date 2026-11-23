package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * How many apps and sites this installation holds, as its operator's dashboard and Sites list count them: what a
 * server tells the servers it is linked to, and what its own row of the Servers list shows.
 *
 * AIDEV-NOTE: read without a viewer (a linked server asks, holding no permission), so it reads the rows the operator's
 * entries hold through their own declarations, never a copy of them: the Instances list's scope
 * (InstanceParts.listedByOperator), the soft-delete scope every default read keeps, and the one rule of what an app is
 * (AppDirectory.Members). Three reads of keys and links, whatever the installation holds.
 *
 * @param apps  the apps, as the dashboard's Apps tile counts them for an operator
 * @param sites the sites, as the Sites list holds them for an operator
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public record InstallationCounts(int apps, int sites) {

    /** @return this installation's counts now */
    public static @NonNull InstallationCounts read() {
        List<Row> sites = Models.get(SiteModel.class).find().noCache().only(SiteModel.ID, SiteModel.INSTANCE_ID).all();
        List<Row> instances = Models.get(InstanceModel.class).find().noCache()
            .where(InstanceParts.listedByOperator()).only(InstanceModel.ID, InstanceModel.GENERATED_BY).all();
        List<Row> stacks = Models.get(StackModel.class).find().noCache().only(StackModel.ID).all();
        return new InstallationCounts(AppDirectory.Members.of(sites, instances, stacks).apps(), sites.size());
    }
}
