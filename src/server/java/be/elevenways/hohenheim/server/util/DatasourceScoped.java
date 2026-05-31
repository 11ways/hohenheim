package be.elevenways.hohenheim.server.util;

import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;

import java.util.function.Supplier;

/**
 * Base for services whose model access must run against a specific datasource. Production uses the
 * registered default (null override); tests inject an isolated datasource, applied via a {@link Db}
 * scope around each model operation -- so the framework's model singletons resolve the right one
 * without a per-test global default (which would clobber the shared runtime datasource).
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public abstract class DatasourceScoped {

    private final Datasource datasource;   // null -> use the registered default

    protected DatasourceScoped(Datasource datasource) {
        this.datasource = datasource;
    }

    /** Run a value-returning model operation under this service's datasource scope. */
    protected <T> T query(Supplier<T> body) {
        return datasource == null ? body.get() : Db.supply(datasource, body);
    }

    /** Run a void model operation under this service's datasource scope. */
    protected void exec(Runnable body) {
        if (datasource == null) {
            body.run();
        } else {
            Db.run(datasource, body);
        }
    }
}
