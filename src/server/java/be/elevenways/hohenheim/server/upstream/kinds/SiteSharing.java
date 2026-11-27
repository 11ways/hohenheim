package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.ProtectedPathParts;
import be.elevenways.hohenheim.server.files.SiteFiles;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.hohenheim.site.SiteFileSuggestions;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.RelatedList;
import be.elevenways.zenit.common.data.DataProvider;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.http.CacheDirectives;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.ListField;
import be.elevenways.zenit.common.orm.field.MapEntries;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.validation.ValidationContext;
import be.elevenways.zenit.common.validation.ValidationResult;
import be.elevenways.zenit.server.http.OriginComparator;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE home of what a site shares with browsers and who may fetch what from it, the folded "Sharing and downloads"
 * section after the site form's own sections: which other websites may read its answers, how long a browser may keep
 * its files, and its private paths ({@link #addTo}).
 *
 * AIDEV-NOTE: the settings are settings keys, never columns, like {@code own_files}: no migration carries them,
 * and every kind's tool takes them by the same names because each kind's schema holds the same fields. The proxy reads
 * them once per route (SharingAnswers); a redirect, a stream and a TLS passthrough answer no content, so they declare
 * none. The private paths are the site's protected paths read through the Protection tab's own child list, never
 * stored here and never written here.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SiteSharing {

    /** The stored key of who may read the site's answers from another website ({@link Readers}). */
    public static final String READABLE_BY = "readable_by_websites";

    /** The stored key of the websites that may read the site's answers while {@link Readers#LISTED}. */
    public static final String READING_WEBSITES = "reading_websites";

    /** The stored key of how long browsers may keep files, by path ({@link Keep}). */
    public static final String BROWSER_KEEP = "browser_keep";

    /** The member a tool names a path's choice by in {@link #BROWSER_KEEP}. */
    public static final String KEEP_MEMBER = "keep";

    /** The member a tool names a path by in {@link #BROWSER_KEEP}. */
    public static final String PATH_MEMBER = "path";

    /** The section's entry listing the site's protected paths, which holds no value. */
    public static final String PRIVATE_PATHS = "private_paths";

    /** Who may read the site's answers from another website. */
    public enum Readers {
        NOBODY("nobody"),
        LISTED("listed"),
        ANY("any");

        private final @NonNull String token;

        Readers(@NonNull String token) {
            this.token = token;
        }

        /** @return the stored and tool spelling */
        public @NonNull String token() {
            return this.token;
        }

        /** @return the member a stored token spells; nobody for none or one this build does not know */
        public static @NonNull Readers of(@Nullable Object token) {
            for (Readers each : values()) {
                if (each.token.equals(token)) return each;
            }
            return NOBODY;
        }
    }

    /** How long a browser may keep a file, each choice with the Cache-Control it sends. */
    public enum Keep {
        /** No header is added: the browser and the file's own validators decide. */
        DECIDE("decide", null),
        /** The browser keeps the file but asks again before every use, a cheap 304 while it is unchanged. */
        ASK_AGAIN("ask_again", CacheDirectives.REVALIDATE),
        /** The browser keeps the file a year without asking again. */
        YEAR("year", CacheDirectives.IMMUTABLE);

        private final @NonNull String token;
        private final @Nullable String directive;

        Keep(@NonNull String token, @Nullable String directive) {
            this.token = token;
            this.directive = directive;
        }

        /** @return the stored and tool spelling */
        public @NonNull String token() {
            return this.token;
        }

        /** @return the Cache-Control value it sends, null for none */
        public @Nullable String directive() {
            return this.directive;
        }

        /** @return the member a stored token spells, null for one this build does not know */
        public static @Nullable Keep of(@Nullable Object token) {
            for (Keep each : values()) {
                if (each.token.equals(token)) return each;
            }
            return null;
        }

        private @NonNull Microcopy label() {
            return HohenheimMicrocopy.HOHENHEIM_FIELD.of("browser_keep_" + this.token);
        }
    }

    private SiteSharing() {
    }

    /**
     * Declares the two settings and the site's private paths on a kind's settings schema, folded in one section drawn
     * after the site form's own sections, after "Advanced".
     *
     * AIDEV-NOTE: the settings stay KIND settings although every kind that answers with content declares the same
     * ones: the kind's schema is what stores them in the site's settings (no column, no migration), what the tools
     * derive their arguments from and what a redirect, a stream and a TLS passthrough leave out. Where they DRAW is the
     * form's business, so the section says it trails its host form (FormSection.afterHostSections). The private paths
     * are an entry holding no value (Schema.addEntry): the site's protected paths with how one gets in, an Edit link
     * each and the site's "Protect a path", the same records and the one writer of the Protection tab.
     *
     * @return the schema
     */
    public static @NonNull Schema addTo(@NonNull Schema schema) {
        EnumField.Builder readers = EnumField.builder(READABLE_BY).defaultValue(Readers.NOBODY.token())
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of(READABLE_BY))
            .help(HohenheimMicrocopy.HELP.of(READABLE_BY))
            .presentation(Select.Presentation.SEGMENTS)
            .keepsAValue();
        for (Readers each : Readers.values()) {
            readers.value(each.token(), each.token(), HohenheimMicrocopy.HOHENHEIM_FIELD.of("readable_by_"
                + each.token()));
        }
        schema.addField(readers.build());
        StringField website = StringField.builder("website").placeholder("https://")
            .validator(SiteSharing::refuseNoWebsite).build();
        schema.addField(ListField.<String>builder(website).name(READING_WEBSITES)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of(READING_WEBSITES))
            .help(HohenheimMicrocopy.HELP.of(READING_WEBSITES))
            .addLabel(HohenheimMicrocopy.HOHENHEIM_FIELD.of("reading_websites_add"))
            .unordered()
            .build());
        schema.showWhen(READING_WEBSITES, READABLE_BY, Readers.LISTED.token());
        List<FieldOption<String>> choices = new ArrayList<>();
        for (Keep each : Keep.values()) {
            choices.add(FieldOption.of(each.token(), each.label()));
        }
        schema.addField(StringMapField.builder(BROWSER_KEEP)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of(BROWSER_KEEP))
            .help(HohenheimMicrocopy.HELP.of(BROWSER_KEEP))
            .keyLabel(HohenheimMicrocopy.HOHENHEIM_FIELD.of("browser_keep_path"))
            .valueLabel(HohenheimMicrocopy.HOHENHEIM_FIELD.of("browser_keep_choice"))
            .entries(MapEntries.keyedBy(PATH_MEMBER, HohenheimMicrocopy.HELP.of("browser_keep_path"))
                .choice(KEEP_MEMBER, choices, HohenheimMicrocopy.HELP.of("browser_keep_choice")))
            .addLabel(HohenheimMicrocopy.HOHENHEIM_FIELD.of("browser_keep_add"))
            .otherwise(Keep.DECIDE.label())
            .keySuggestions(SiteSharing::fileSuggestions)
            .validator(SiteSharing::refuseUnreadableKeep)
            .build());
        schema.addEntry(RelatedList.of(PRIVATE_PATHS, HohenheimSlugs.SITES, privatePaths(),
            HohenheimMicrocopy.HOHENHEIM_FIELD.of(PRIVATE_PATHS),
            HohenheimMicrocopy.HOHENHEIM_FIELD.of("private_paths_edit"),
            HohenheimMicrocopy.HOHENHEIM_FIELD.of("private_paths_none")));
        schema.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.SHARING,
            HohenheimMicrocopy.FORM_SECTION.of("sharing_description"),
            List.of(READABLE_BY, READING_WEBSITES, BROWSER_KEEP, PRIVATE_PATHS)).afterHostSections());
        return schema;
    }

    /**
     * @return the site's protected paths as its Protection tab lists them, each its path and how one gets in, with
     *         the site's own "Protect a path"
     */
    private static @NonNull ChildList<Object> privatePaths() {
        return ChildList.sections(HohenheimSlugs.PROTECTED_PATHS,
                HohenheimMicrocopy.HOHENHEIM_FIELD.of(PRIVATE_PATHS), HohenheimSlugs.PROTECTED_PATHS)
            .columns(HohenheimSlugs.PROTECTED_PATHS, ProtectedPathModel.PATH.getName(), ProtectedPathParts.WAY_COLUMN)
            .parentActions(HohenheimSlugs.PROTECTED_PATHS, ProtectPath.OPERATION.id());
    }

    /** @return the suggestions of a typed path: the edited site's own files, none for a site serving none */
    private static @Nullable DataProvider fileSuggestions(@Nullable Object record) {
        return record instanceof Row site && SiteFiles.servesOwnFiles(site)
            ? new SiteFileSuggestions(site.get(SiteModel.ID)) : null;
    }

    /** @return the normalized website a full address names (scheme, host and port alone), null for anything else */
    public static @Nullable String websiteOf(@Nullable String address) {
        return address == null ? null : OriginComparator.normalize(address.strip());
    }

    private static @NonNull ValidationResult refuseNoWebsite(@Nullable String address,
                                                             @NonNull ValidationContext context) {
        if (address == null || address.isBlank() || websiteOf(address) != null) {
            return ValidationResult.valid();
        }
        return ValidationResult.invalid(HohenheimMicrocopy.VIOLATIONS.of("sharing_website_unreadable")
            .withArg("address", address.strip()));
    }

    private static @NonNull ValidationResult refuseUnreadableKeep(@Nullable Map<String, String> keep,
                                                                  @NonNull ValidationContext context) {
        if (keep == null) {
            return ValidationResult.valid();
        }
        for (Map.Entry<String, String> rule : keep.entrySet()) {
            if (rule.getKey() == null || !rule.getKey().startsWith("/")) {
                return ValidationResult.invalid(HohenheimMicrocopy.VIOLATIONS.of("sharing_path_unrooted")
                    .withArg("path", String.valueOf(rule.getKey())));
            }
            if (Keep.of(rule.getValue()) == null) {
                return ValidationResult.invalid(HohenheimMicrocopy.VIOLATIONS.of("sharing_keep_unknown")
                    .withArg("path", rule.getKey()));
            }
        }
        return ValidationResult.valid();
    }
}
