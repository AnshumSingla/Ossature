package com.ossature.interviewer.service;

import java.util.regex.Pattern;

/**
 * Characters that have no business in a person's text and cause trouble downstream: control characters (a NUL
 * byte makes Postgres refuse the whole row), terminal escape sequences that would reach logs, and Unicode line
 * separators and bidirectional overrides that can make text display as something it is not. Newline, carriage
 * return and tab are allowed (prompts and transcripts legitimately contain them).
 */
public final class TextSafety {

    private static final Pattern UNSAFE = Pattern.compile("[[\\p{Cc}&&[^\\n\\r\\t]]\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]");

    private TextSafety() {}

    public static boolean isSafe(String text) {
        return text == null || !UNSAFE.matcher(text).find();
    }

    /** The text with every unsafe character removed (for input that is processed rather than validated, e.g. speech). */
    public static String clean(String text) {
        return text == null ? null : UNSAFE.matcher(text).replaceAll("");
    }
}
