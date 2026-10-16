package com.ossature.interviewer.web;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.db.DbContext;
import com.ossature.interviewer.service.AuthService;
import com.ossature.interviewer.service.Emails;
import com.ossature.interviewer.service.RateLimiter;
import com.ossature.interviewer.service.TooManyRequestsException;
import com.ossature.interviewer.web.dto.AuthDtos.AuthResponse;
import com.ossature.interviewer.web.dto.AuthDtos.LoginRequest;
import com.ossature.interviewer.web.dto.AuthDtos.SignupRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final RateLimiter rateLimiter;
    private final AppProperties.RateLimit limits;

    public AuthController(AuthService auth, RateLimiter rateLimiter, AppProperties props) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.limits = props.rateLimit();
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody SignupRequest req, HttpServletRequest http) {
        guardSignupAttempt(http);
        // Set before the service's transaction begins (see DbContext): only this scope may create accounts.
        return DbContext.callInScope(DbContext.SCOPE_AUTH, () -> auth.signup(req));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        guardLoginAttempt(req.email(), http);
        return DbContext.callInScope(DbContext.SCOPE_AUTH, () -> auth.login(req));
    }

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

    /** Signup is throttled per source IP only; it deliberately shares nothing with login's budgets. */
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
