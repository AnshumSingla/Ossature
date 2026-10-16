package com.ossature.interviewer.service;

import java.util.Locale;

/**
 * One canonical form for email addresses: trimmed and lower-cased. Everything that stores,
 * looks up, rate-limits on, or role-checks an email must go through this, otherwise
 * {@code Owner@x.com} and {@code owner@x.com} become two different accounts that the owner
 * check (case-insensitive) treats as the same person.
 */
public final class Emails {

    private Emails() {}

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
