package com.ossature.interviewer.service;

/** A signed-in user re-entered their password to confirm something destructive, and it was wrong. */
public class WrongPasswordException extends RuntimeException {
    public WrongPasswordException() {
        super("That password isn't correct.");
    }
}
