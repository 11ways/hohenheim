package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.zenit.cms.server.migration.LegacyResourceSubject;

import java.util.function.Function;

/**
 * Frozen pre-v3 resource subjects; migration mode does not construct Hohenheim's panels.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@BlastAutoLoad
public final class HohenheimLegacyResourceSubjects {
    public static final LegacyResourceSubject ENVIRONMENT = LegacyResourceSubject.declare(HohenheimLegacyResourceSubjects.class,
        HohenheimIds.id("environment_variable"), HohenheimIds.id("instance_variable"), Function.identity());
    public static final LegacyResourceSubject AUTH_PROVIDER = LegacyResourceSubject.declare(HohenheimLegacyResourceSubjects.class,
        HohenheimIds.id("auth_provider"), HohenheimIds.id("site_auth_provider"), Function.identity());
    public static final LegacyResourceSubject ACCESS_RULE = LegacyResourceSubject.declare(HohenheimLegacyResourceSubjects.class,
        HohenheimIds.id("manage_access_rule"), HohenheimIds.id("access_rule"), Function.identity());
    public static final LegacyResourceSubject GIT_PROVIDER = LegacyResourceSubject.declare(HohenheimLegacyResourceSubjects.class,
        HohenheimIds.id("manage_git_provider"), HohenheimIds.id("git_provider"), Function.identity());

    private HohenheimLegacyResourceSubjects() {}
}
