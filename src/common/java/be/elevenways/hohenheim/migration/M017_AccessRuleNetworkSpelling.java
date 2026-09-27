package be.elevenways.hohenheim.migration;

import be.elevenways.hohenheim.net.LegacyIpSpellings;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites every stored access-rule network that zenit's strict literal parser now refuses into
 * the canonical spelling of the address the previous build read it as.
 *
 * AIDEV-NOTE: zenit's IpRanges.parseLiteral used to accept a zone id (dropped), IPv4 octets with
 * leading zeros (read as DECIMAL) and non-ASCII digits in IPv6 groups. A rule stored in such a
 * spelling now parses to nothing, and an unparseable ip_deny leaf PASSES (AccessRuleTree), so
 * leaving it would silently widen access. {@link LegacyIpSpellings} is the previous reading; a value
 * it also refused matched nothing before and is left untouched.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public class M017_AccessRuleNetworkSpelling extends HohenheimMigration {

    /** The rule types that carry a network, as production stored them. */
    static final Set<String> NETWORK_TYPES = Set.of("ip_allow", "ip_deny");

    /** The data key holding the network, as production stored it. */
    static final String NETWORK_KEY = "network";

    public M017_AccessRuleNetworkSpelling() {
        super("017", "Access rule network spelling");
        dependsOn(M016_InstanceWorkloadKilled.class);
        irreversible("the original spellings are replaced by their canonical form; the previous build"
            + " reads the canonical form as the same address, so nothing needs restoring");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.data("rewrite access-rule networks the strict parser refuses to their canonical spelling", "1",
            M017_AccessRuleNetworkSpelling::canonicalizeNetworks);
    }

    /** Never run: the migration is declared irreversible, and the executor refuses the DOWN first. */
    @Override
    public void down(@NonNull MigrationBuilder schema) {
        throw new UnsupportedOperationException("M017 is irreversible");
    }

    /** The data step. */
    public static void canonicalizeNetworks(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField type = StringField.builder().name("type").build();
            SchemaField data = SchemaField.builder("data").build();
            StringField searchText = StringField.builder().name("search_text").build();
            FrozenModel rules = new FrozenModel("access_rules", id, type, data, searchText);
            for (Row row : rules.find().where(type.in(List.copyOf(NETWORK_TYPES))).all()) {
                if (!(row.get(data) instanceof Map<?, ?> stored)
                        || !(stored.get(NETWORK_KEY) instanceof String network)) {
                    continue;
                }
                String canonical = LegacyIpSpellings.canonicalNetwork(network);
                if (canonical == null || canonical.equals(network)) {
                    continue;
                }
                Map<String, Object> rewritten = new LinkedHashMap<>();
                stored.forEach((key, value) -> rewritten.put(String.valueOf(key), value));
                rewritten.put(NETWORK_KEY, canonical);
                row.set(data, rewritten);
                String text = row.get(searchText);
                if (text != null) {
                    row.set(searchText, text.replace(network.trim(), canonical));
                }
                rules.save(row);
            }
        });
    }
}
