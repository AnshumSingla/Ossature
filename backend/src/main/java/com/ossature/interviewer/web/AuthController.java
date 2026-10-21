package com.ossature.interviewer.web;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.db.DbContext;
import com.ossature.interviewer.service.AuthService;
import com.ossature.interviewer.service.Emails;
import com.ossature.interviewer.service.RateLimiter;
import com.ossature.interviewer.service.RefreshTokenService;
import com.ossature.interviewer.service.RefreshTokenService.Issued;
import com.ossature.interviewer.service.TooManyRequestsException;
import com.ossature.interviewer.web.dto.AuthDtos.AuthResponse;
import com.ossature.interviewer.web.dto.AuthDtos.LoginRequest;
import com.ossature.interviewer.web.dto.AuthDtos.SignupRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sign-up, login, and the refresh-token session (H10).
 *
 * <p>Login and sign-up return a short-lived access token in the body (kept in memory by the browser) and
 * set the refresh token as an httpOnly, SameSite=Strict cookie that is only ever sent to {@code /api/auth}.
 * {@code /refresh} and {@code /logout} authenticate by that cookie alone, which is exactly what a
 * cross-site request forgery would exploit, so they also require a custom request header (which a
 * cross-site form or image cannot set, and which makes a cross-origin call go through CORS) and refuse a
 * request whose {@code Origin} is not an allowed one.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Set by the web client on cookie-authenticated calls; its presence is the CSRF check. */
    static final String CLIENT_HEADER = "X-Ossature-Client";
    private static final int REFRESH_MAX_PER_IP = 60;
    private static final long REFRESH_WINDOW_MS = Duration.ofMinutes(5).toMillis();

    private final AuthService auth;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookies cookies;
    private final RateLimiter rateLimiter;
    private final AppProperties.RateLimit limits;
    private final List<String> allowedOrigins;

    public AuthController(AuthService auth, RefreshTokenService refreshTokens, RefreshCookies cookies,
                          RateLimiter rateLimiter, AppProperties props) {
        this.auth = auth;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.rateLimiter = rateLimiter;
        this.limits = props.rateLimit();
        this.allowedOrigins = props.cors() == null || props.cors().allowedOrigins() == null
                ? List.of() : props.cors().allowedOrigins();
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest req, HttpServletRequest http) {
        guardSignupAttempt(http);
        // Set before the service's transaction begins (see DbContext): only this scope may create accounts.
        AuthResponse body = DbContext.callInScope(DbContext.SCOPE_AUTH, () -> auth.signup(req));
        return respond(HttpStatus.CREATED, body, true);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        guardLoginAttempt(req.email(), http);
        AuthResponse body = DbContext.callInScope(DbContext.SCOPE_AUTH, () -> auth.login(req));
        return respond(HttpStatus.OK, body, req.remember());
    }

    /** Trades the refresh cookie for a new access token (and a rotated cookie). */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest http) {
        requireOurOwnClient(http);
        if (!rateLimiter.allow("refresh:ip:" + clientIp(http), REFRESH_MAX_PER_IP, REFRESH_WINDOW_MS)) {
            throw new TooManyRequestsException("Too many session refreshes from this address - try again shortly");
        }
        String presented = cookies.presented(http);
        Optional<Issued> next = DbContext.callInScope(DbContext.SCOPE_AUTH, () -> refreshTokens.rotate(presented));
        Optional<AuthResponse> body = next.flatMap(i ->
                DbContext.callInScope(DbContext.SCOPE_AUTH, () -> auth.sessionFor(i.userId())));
        if (next.isEmpty() || body.isEmpty()) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                    "Your session has ended. Please log in again.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.SET_COOKIE, cookies.cleared().toString()).body(pd);
        }
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.issue(next.get()).toString()).body(body.get());
    }

    /** Ends this login session on the server (revokes the refresh chain) and clears the cookie. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        requireOurOwnClient(http);
        String presented = cookies.presented(http);
        DbContext.callInScope(DbContext.SCOPE_AUTH, () -> {
            refreshTokens.end(presented);
            return null;
        });
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.cleared().toString()).build();
    }

    // ------------------------------------------------------------------ cookie and CSRF

    private ResponseEntity<AuthResponse> respond(HttpStatus status, AuthResponse body, boolean persistent) {
        Issued session = DbContext.callInScope(DbContext.SCOPE_AUTH, () -> refreshTokens.start(body.userId(), persistent));
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, cookies.issue(session).toString()).body(body);
    }

    private void requireOurOwnClient(HttpServletRequest http) {
        if (http.getHeader(CLIENT_HEADER) == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing client header");
        }
        String origin = http.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Origin not allowed");
        }
    }

    // ------------------------------------------------------------------ rate limits

    /**
     * Three budgets, any one tripping blocks the call (see {@code AppProperties.RateLimit}):
     * per (account, IP), per account overall, and per IP. The first is what stops one source
     * guessing a password; keying it on the IP as well means a stranger's wrong guesses can
     * only ever use up their own budget, never the real owner's.
     */
    private void guardLoginAttempt(String rawEmail, HttpServletRequest http) {
        // Normalised so "A@x.com" and "a@x.com" share one budget - otherwise changing the case
        // of the email would hand an attacker a fresh set of guesses.
        String email = Emails.normalize(rawEmail);
        String ip = clientIp(http);
        if (!rateLimiter.allow("login:pair:" + email + "|" + ip, limits.loginMaxPerEmailAndIp(),
                limits.loginEmailIpWindow().toMillis())) {
            throw new TooManyRequestsException("Too many attempts for this account from your address - try again shortly");
        }
        if (!rateLimiter.allow("login:email:" + email, limits.loginMaxPerEmailGlobal(),
                limits.loginEmailGlobalWindow().toMillis())) {
            throw new TooManyRequestsException("Too many attempts for this account - try again later");
        }
        if (!rateLimiter.allow("login:ip:" + ip, limits.loginMaxPerIp(), limits.loginIpWindow().toMillis())) {
            throw new TooManyRequestsException("Too many attempts from this address - try again shortly");
        }
    }

    private void guardSignupAttempt(HttpServletRequest http) {
        if (!rateLimiter.allow("signup:ip:" + clientIp(http), limits.signupMaxPerIp(), limits.signupWindow().toMillis())) {
            throw new TooManyRequestsException("Too many sign-ups from this address - try again later");
        }
    }

    private static String clientIp(HttpServletRequest http) {
        // Deliberately NOT trusting X-Forwarded-For: there's no reverse proxy in front of
        // this yet to strip/overwrite a client-supplied value, so honoring it would let an
        // attacker set a fresh fake IP on every request and bypass this limiter entirely.
        // Revisit once this sits behind a proxy that can be trusted to set it correctly.
        return http.getRemoteAddr();
    }
}
