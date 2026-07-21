package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.Array;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.submit.SubmittedValueCoercion;
import be.elevenways.zenit.common.orm.field.ListField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.forms.common.render.FormState;
import be.elevenways.zenit.forms.server.render.FormStateTranslator;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared typed form definition for the certificate request's repeatable domains. */
public final class CertificateRequestForm {

    private static final StringField DOMAIN = StringField.builder("domain")
        .placeholder("example.com")
        .build();

    private static final ListField<String> DOMAINS = ListField.<String>builder(DOMAIN)
        .name("domains")
        .label(Microcopy.of("domains").withFilter("scope", "certificate_request"))
        .help(Microcopy.of("domains_help").withFilter("scope", "certificate_request"))
        .build();

    public static final FormSpec SPEC = FormSpec.builder()
        .add(Array.of(DOMAINS, DOMAIN).minCount(1).build())
        .build();

    private CertificateRequestForm() {
    }

    public static @NonNull FormState state(@NonNull AccessContext accessContext,
                                            @NonNull List<String> domains) {
        return new FormStateTranslator().translate(
            SPEC, Map.of(), EditView.CREATE, accessContext, Map.of("domains", domains), List.of(), null);
    }

    public static @NonNull List<String> submittedDomains(@NonNull Map<String, Object> rawValues) {
        Object value = SubmittedValueCoercion.coerceFormOrThrow(SPEC, rawValues).get("domains");
        if (!(value instanceof List<?> values)) {
            return List.of();
        }

        List<String> domains = new ArrayList<>(values.size());
        for (Object item : values) {
            String domain = item == null ? "" : String.valueOf(item).trim();
            if (!domain.isEmpty()) {
                domains.add(domain);
            }
        }
        return List.copyOf(domains);
    }
}
