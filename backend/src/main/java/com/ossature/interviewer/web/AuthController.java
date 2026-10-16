package com.ossature.interviewer.web;

import com.ossature.interviewer.config.AppProperties;
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
        // Same guard as login - an unthrottled signup endpoint is just as easy to script.
        guardLoginAttempt(req.email(), http);
        return auth.signup(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        guardLoginAttempt(req.email(), http);
        return auth.login(req);
    }

    /**
     * Two independent windows: per-email (stops repeated guesses against one account) and
     * per-IP (stops one source spraying guesses across many emails) - either tripping blocks
     * the call (PRD 6.2 auth follow-up).
     */
    private void guardLoginAttempt(String rawEmail, HttpServletRequest http) {
        // Normalised so "A@x.com" and "a@x.com" share one attempt budget - otherwise changing
        // the case of the email would hand an attacker a fresh set of guesses.
        String email = Emails.normalize(rawEmail);
        if (!rateLimiter.allow("login:email:" + email, limits.loginMaxPerEmail(), limits.loginWindow().toMillis())) {
            throw new TooManyRequestsException("Too many attempts for this account - try again shortly");
        }
        if (!rateLimiter.allow("login:ip:" + clientIp(http), limits.loginMaxPerIp(), limits.loginIpWindow().toMillis())) {
            throw new TooManyRequestsException("Too many attempts from this address - try again shortly");
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
