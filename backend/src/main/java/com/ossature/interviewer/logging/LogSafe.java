package com.ossature.interviewer.logging;

import java.util.Arrays;
import org.slf4j.Logger;
import org.springframework.web.client.RestClientResponseException;

/**
 * Logs a failure without its message.
 *
 * <p>Exception messages are where request and response bodies leak: a failed agent call can carry the
 * candidate's transcript, a JSON parse error quotes the text it choked on. So warnings record only the
 * failure's <i>type</i> (and HTTP status), which is what you need to tell what went wrong; the full
 * stack trace is available at DEBUG for local debugging and is still run through {@link Masking}.
 */
public final class LogSafe {

    private LogSafe() {}

    /** e.g. {@code HttpServerErrorException (HTTP 500) caused by ConnectException}. No messages. */
    public static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t instanceof RestClientResponseException r) {
            sb.append(" (HTTP ").append(r.getStatusCode().value()).append(')');
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root != t) {
            sb.append(" caused by ").append(root.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /**
     * {@code message} may contain {@code {}} placeholders filled from {@code args}. Logs at WARN with the
     * failure type appended, and the full stack trace at DEBUG.
     */
    public static void warn(Logger log, String message, Throwable t, Object... args) {
        if (log.isWarnEnabled()) {
            Object[] withCause = Arrays.copyOf(args, args.length + 1);
            withCause[args.length] = describe(t);
            log.warn(message + ": {}", withCause);
        }
        if (log.isDebugEnabled()) {
            Object[] withThrowable = Arrays.copyOf(args, args.length + 1);
            withThrowable[args.length] = t; // SLF4J treats a trailing Throwable as the exception
            log.debug(message + " (details)", withThrowable);
        }
    }
}
