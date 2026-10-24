package com.ossature.interviewer.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Refresh-token (login session) settings, {@code ossature.refresh.*} (H10).
 *
 * @param rememberTtl  how long a "remember me" login lasts, absolute from the login
 * @param sessionTtl   how long a login lasts when the user did not ask to be remembered (the cookie is also
 *                     a browser-session cookie, so closing the browser ends it sooner)
 * @param reuseGrace   a token used again this soon after its first use is treated as two tabs refreshing at
 *                     once, not as theft; later reuse revokes the whole login
 * @param cookieSecure send the cookie over HTTPS only. Must be true in production (checked at startup);
 *                     false only so a plain-http local development setup works in browsers that require it
 * @param cookieName   name of the httpOnly refresh cookie
 */
@ConfigurationProperties(prefix = "ossature.refresh")
public record RefreshProperties(
        @DefaultValue("30d") Duration rememberTtl,
        @DefaultValue("12h") Duration sessionTtl,
        @DefaultValue("10s") Duration reuseGrace,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("ossature_refresh") String cookieName) {}
