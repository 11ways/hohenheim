package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.server.orm.GeneratedRows;

/**
 * The attribution guard for instance config files a system authored (the game-domains
 * Velocity forced-hosts materialization): the same DERIVED-never-submitted discipline as
 * {@code GeneratedDnsRecords}, over the SAME shared {@link GeneratedRows} scope, so one
 * scope block covers a materialization that writes files and DNS rows together.
 *
 * @author Jelle De Loecker
 */
public final class GeneratedInstanceFiles {

    private static volatile boolean installed;

    private GeneratedInstanceFiles() {
    }

    /** Install the attribution invariant on the InstanceFileModel write pipeline. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        GeneratedRows.installGuards(InstanceFileModel.SCHEMA, InstanceFileModel.class,
            new GeneratedRows.Columns(InstanceFileModel.GENERATED_BY,
                InstanceFileModel.GENERATED_FOR_MODEL, InstanceFileModel.GENERATED_FOR_ID,
                InstanceFileModel.GENERATED_AT),
            "file_generated_readonly", "file_generated_attribution");
    }
}
