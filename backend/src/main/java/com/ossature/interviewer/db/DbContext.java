package com.ossature.interviewer.db;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is acting, as far as the database is concerned (H17).
 *
 * <p>{@link RlsJpaTransactionManager} copies this into Postgres settings at the start of every
 * transaction (see {@code V7__row_level_security.sql}), where the row-level-security policies use it
 * to decide which rows are visible. Three sources, in order:
 * <ol>
 *   <li>an explicit {@link #runAs}/{@link #runInScope} block - for code that runs outside an HTTP
 *       request (the WebSocket handler, the summary scheduler) or needs a special scope (login);</li>
 *   <li>the signed-in user of the current HTTP request (Spring Security);</li>
 *   <li>nothing - an anonymous request, for which the policies show only public data.</li>
 * </ol>
 *
 * <p>It must be set <i>before</i> the transaction begins, so use it at the edge (controller, socket
 * handler, scheduler), not inside a {@code @Transactional} service method.
 */
public final class DbContext {

    /** Names that the policies in V7 recognise. Anything else matches no scoped policy. */
    public static final String SCOPE_AUTH = "auth";
    public static final String SCOPE_SCHEDULER = "scheduler";
    /** The data-retention purge: may delete sessions older than the database's 30-day floor, nothing else. */
    public static final String SCOPE_RETENTION = "retention";

    public record Ctx(UUID userId, boolean owner, String scope) {}

    private static final ThreadLocal<Ctx> OVERRIDE = new ThreadLocal<>();

    private DbContext() {}

    /** The acting context for this thread, or null for an anonymous caller. */
    public static Ctx current() {
        Ctx explicit = OVERRIDE.get();
        if (explicit != null) {
            return explicit;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UUID id) {
            boolean owner = auth.getAuthorities().stream().anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
            return new Ctx(id, owner, null);
        }
        return null;
    }

    /** Runs {@code body} acting as {@code userId}. */
    public static <T> T callAs(UUID userId, boolean owner, Supplier<T> body) {
        return with(new Ctx(userId, owner, null), body);
    }

    public static void runAs(UUID userId, boolean owner, Runnable body) {
        callAs(userId, owner, () -> {
            body.run();
            return null;
        });
    }

    /** Runs {@code body} in a named scope with no user (login/signup, the scheduler's session listing). */
    public static <T> T callInScope(String scope, Supplier<T> body) {
        return with(new Ctx(null, false, scope), body);
    }

    /** Acting as {@code userId} and, additionally, in {@code scope} (account deletion: your own rows, in the auth scope). */
    public static <T> T callAsInScope(UUID userId, String scope, Supplier<T> body) {
        return with(new Ctx(userId, false, scope), body);
    }

    private static <T> T with(Ctx ctx, Supplier<T> body) {
        Ctx previous = OVERRIDE.get();
        OVERRIDE.set(ctx);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }
}
