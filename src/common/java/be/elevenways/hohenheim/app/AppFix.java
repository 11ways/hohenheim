package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.render.action.LinkActionState;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The one fix an app's row in the Apps list offers (board Apps-List: "Get a certificate" on a broken row): the first
 * fix its verdict names that the reader may use, exactly as that record's own band offers it.
 *
 * @param link   the fix when it leads somewhere (Get a certificate, Check and admit), else null
 * @param invoke the fix when it runs on the spot (Restart, Stop forcing HTTPS), else null
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record AppFix(@Nullable LinkActionState link, @Nullable InvokeActionState invoke) {
}
