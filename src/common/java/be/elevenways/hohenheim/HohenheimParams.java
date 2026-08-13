package be.elevenways.hohenheim;

import be.elevenways.zenit.common.routing.ParameterDefinition;

/**
 * THE declared vocabulary of Hohenheim's query parameters: the prefill values a CMS
 * create form is opened with and the selection state a record subpage carries.
 *
 * AIDEV-NOTE: every one of these is addressable page STATE -- it belongs in a URL a
 * reader can bookmark and share. NOTIFICATIONS do not: the seven error/saved/restored/
 * imported/skipped/exit/output parameters that used to live here were deleted because
 * they put operator-facing text (and command output) into logged, shareable URLs. Use
 * {@code HohenheimFlash} for an outcome message and a session stash for page content.
 * A CMS route plus one of these is {@code CmsRoutes.x(...).with(PARAM, value)} --
 * never a concatenated {@code "?site_id=" + id}.
 */
public final class HohenheimParams {

    private HohenheimParams() {
    }

    /** Prefill for a create form whose record belongs to a site. */
    public static final ParameterDefinition<Integer> SITE_ID_PREFILL =
        ParameterDefinition.builder(Integer.class).name("site_id")
            .stringResolver(Integer::parseInt).build();

    /** Prefill for a create form whose record belongs to an instance. */
    public static final ParameterDefinition<Integer> INSTANCE_ID_PREFILL =
        ParameterDefinition.builder(Integer.class).name("instance_id")
            .stringResolver(Integer::parseInt).build();

    /** Prefill for a create form whose record belongs to an instance template. */
    public static final ParameterDefinition<Integer> TEMPLATE_ID_PREFILL =
        ParameterDefinition.builder(Integer.class).name("template_id")
            .stringResolver(Integer::parseInt).build();

    /** Prefill for a create form whose record belongs to a DNS zone. */
    public static final ParameterDefinition<Integer> ZONE_ID_PREFILL =
        ParameterDefinition.builder(Integer.class).name("zone_id")
            .stringResolver(Integer::parseInt).build();

    /** Prefill for a create form whose record belongs to a stack. */
    public static final ParameterDefinition<Integer> STACK_ID_PREFILL =
        ParameterDefinition.builder(Integer.class).name("stack_id")
            .stringResolver(Integer::parseInt).build();

    /** Prefill for a create form whose record belongs to a stack service. */
    public static final ParameterDefinition<Integer> STACK_SERVICE_ID_PREFILL =
        ParameterDefinition.builder(Integer.class).name("stack_service_id")
            .stringResolver(Integer::parseInt).build();

    /** Prefill for a create form whose record belongs to a record schedule. */
    public static final ParameterDefinition<Integer> SCHEDULE_ID_PREFILL =
        ParameterDefinition.builder(Integer.class).name("schedule_id")
            .stringResolver(Integer::parseInt).build();

    /** Prefill for the record-schedule create form (the scheduled record's own id). */
    public static final ParameterDefinition<Integer> RECORD_ID_PREFILL =
        ParameterDefinition.builder(Integer.class).name("record_id")
            .stringResolver(Integer::parseInt).build();

    /** Prefill for the instance-device create form: {@code disk} or {@code nic}. */
    public static final ParameterDefinition<String> DEVICE_TYPE_PREFILL =
        ParameterDefinition.builder(String.class).name("type")
            .stringResolver(value -> value).build();

    /** The site a certificate request is opened for. */
    public static final ParameterDefinition<Integer> CERTIFICATE_REQUEST_SITE =
        ParameterDefinition.builder(Integer.class).name("site")
            .stringResolver(Integer::parseInt).build();

    /** The instance template the create-from-template page is opened for. */
    public static final ParameterDefinition<Integer> FROM_TEMPLATE_TEMPLATE =
        ParameterDefinition.builder(Integer.class).name("template")
            .stringResolver(Integer::parseInt).build();

    /** The directory the instance Files tab browses. */
    public static final ParameterDefinition<String> FILES_PATH =
        ParameterDefinition.builder(String.class).name("path")
            .stringResolver(value -> value).build();

    /** The file the instance Files tab opens in its inline editor. */
    public static final ParameterDefinition<String> FILES_EDIT =
        ParameterDefinition.builder(String.class).name("edit")
            .stringResolver(value -> value).build();

    /** The stored log entry a console / processes tab renders inline. */
    public static final ParameterDefinition<Integer> SELECTED_LOG =
        ParameterDefinition.builder(Integer.class).name("log")
            .stringResolver(Integer::parseInt).build();

    /** The remote DNS record the secondary-zone editor opens ({@code new} for a fresh one). */
    public static final ParameterDefinition<String> REMOTE_RECORD =
        ParameterDefinition.builder(String.class).name("record")
            .stringResolver(value -> value).build();

    /** The manual-DNS challenge token the certificate request page resumes with. */
    public static final ParameterDefinition<String> MANUAL_CHALLENGE =
        ParameterDefinition.builder(String.class).name("manual")
            .stringResolver(value -> value).build();
}
