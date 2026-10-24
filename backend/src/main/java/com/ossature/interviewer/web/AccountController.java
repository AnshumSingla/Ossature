package com.ossature.interviewer.web;

import com.ossature.interviewer.db.DbContext;
import com.ossature.interviewer.service.AccountService;
import com.ossature.interviewer.service.RateLimiter;
import com.ossature.interviewer.service.SessionService;
import com.ossature.interviewer.service.TooManyRequestsException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Download or delete everything stored about the signed-in person (H21). */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    public record DeleteAccountRequest(@NotBlank @Size(max = 128) String password) {}

    private final AccountService accounts;
    private final SessionService sessions;
    private final RateLimiter rateLimiter;
    private final RefreshCookies cookies;

    public AccountController(AccountService accounts, SessionService sessions, RateLimiter rateLimiter, RefreshCookies cookies) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.cookies = cookies;
    }

    /** Everything we hold about you, as one JSON document. */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(Authentication auth) {
        UUID user = (UUID) auth.getPrincipal();
        if (!rateLimiter.allow("account-export:" + user, 10, Duration.ofHours(1).toMillis())) {
            throw new TooManyRequestsException("Too many exports - try again later");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ossature-export.json\"")
                .body(accounts.export(user));
    }

    /**
     * Permanently deletes the account, every interview and everything else tied to it, and signs every device
     * out. Needs the password again, so a stolen access token cannot do this.
     */
    @PostMapping("/delete")
    public ResponseEntity<Void> delete(@Valid @RequestBody DeleteAccountRequest req, Authentication auth) {
        UUID user = (UUID) auth.getPrincipal();
        // Few tries: each one is a password guess for someone who already holds a session.
        if (!rateLimiter.allow("account-delete:" + user, 5, Duration.ofHours(1).toMillis())) {
            throw new TooManyRequestsException("Too many attempts - try again later");
        }
        List<UUID> deletedSessions = DbContext.callAsInScope(user, DbContext.SCOPE_AUTH,
                () -> accounts.deleteAccount(user, req.password()));
        deletedSessions.forEach(sessions::forgetAgentState);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.cleared().toString()).build();
    }
}
