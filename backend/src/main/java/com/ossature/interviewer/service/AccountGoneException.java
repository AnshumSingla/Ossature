package com.ossature.interviewer.service;

/**
 * A request carried a valid access token for an account that has since been deleted. Access tokens are verified
 * without a database lookup, so one can outlive its account by up to its 15 minutes; writes check, and answer
 * "sign in again" (the client then finds its refresh session gone too and signs out).
 */
public class AccountGoneException extends RuntimeException {
    public AccountGoneException() {
        super("Your account no longer exists. Please sign in again.");
    }
}
