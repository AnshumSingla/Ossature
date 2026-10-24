package com.ossature.interviewer.web;

import com.ossature.interviewer.config.RefreshProperties;
import com.ossature.interviewer.service.RefreshTokenService.Issued;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

/** Builds and reads the httpOnly refresh-token cookie (H10): one place for its attributes. */
@Component
public class RefreshCookies {

    static final String PATH = "/api/auth";

    private final RefreshProperties props;

    public RefreshCookies(RefreshProperties props) {
        this.props = props;
    }

    public ResponseCookie issue(Issued session) {
        ResponseCookie.ResponseCookieBuilder b = base(session.token());
        if (session.persistent()) {
            b.maxAge(Duration.between(Instant.now(), session.expiresAt()));
        } // else a browser-session cookie: gone when the browser closes
        return b.build();
    }

    public ResponseCookie cleared() {
        return base("").maxAge(0).build();
    }

    public String presented(HttpServletRequest http) {
        Cookie c = WebUtils.getCookie(http, props.cookieName());
        return c == null ? null : c.getValue();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(props.cookieName(), value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path(PATH);
    }
}
