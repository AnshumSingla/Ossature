package com.ossature.interviewer.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts secrets and personal data from text on its way into the logs (H15).
 *
 * <p>This is the last line of defence, applied to every log line and stack trace by the logback
 * converters in {@code logback-spring.xml}. The first line is not logging them at all: callers log
 * failure <i>types</i> via {@link LogSafe}, never exception messages or request/response bodies.
 * Masking exists because a library can still put a token, an email or a body into its own messages.
 */
public final class Masking {

    private Masking() {}

    /** A signed token: three base64url segments, the first starting with the JSON header "{". */
    private static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");

    private static final Pattern BEARER =
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");

    /** token=..., "password": "...", ticket: ..., Authorization=... (query strings, JSON, headers). */
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)\\b(token|ticket|password|passwd|secret|authorization|api[_-]?key)([\"']?\\s*[:=]\\s*[\"']?)([^\\s&\"',;}\\]]+)");

    /** Keeps the first character and the domain: enough to debug, not enough to identify. */
    private static final Pattern EMAIL =
            Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = JWT.matcher(text).replaceAll("[jwt]");
        out = BEARER.matcher(out).replaceAll("Bearer [redacted]");
        out = SECRET_PAIR.matcher(out).replaceAll(m -> {
            // Don't re-mask something an earlier rule already replaced.
            String value = m.group(3);
            String replacement = value.startsWith("[") ? value : "[redacted]";
            return Matcher.quoteReplacement(m.group(1) + m.group(2) + replacement);
        });
        return EMAIL.matcher(out).replaceAll("$1***@$2");
    }
}
