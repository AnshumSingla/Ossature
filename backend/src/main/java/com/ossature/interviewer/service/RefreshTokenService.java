package com.ossature.interviewer.service;

import com.ossature.interviewer.config.RefreshProperties;
import com.ossature.interviewer.db.DbContext;
import com.ossature.interviewer.domain.RefreshToken;
import com.ossature.interviewer.repo.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login sessions that can be ended on the server (H10).
 *
 * <p>The browser holds a short-lived access token in memory; what keeps the user signed in is the
 * refresh token managed here: 256 random bits in an httpOnly cookie, stored only as a SHA-256 hash.
 * Every use <b>rotates</b> it. Using a token a second time is the signature of a leaked copy, so the
 * whole chain ("family") is revoked - except within a few seconds of the first use, when it is far more
 * likely two browser tabs refreshed at once. Logout revokes the family, and every family has an absolute
 * expiry that rotation does not extend.
 *
 * <p>All methods must be called in the {@code auth} database scope (see {@link DbContext}): the caller
 * is presenting a cookie, so there is no signed-in identity yet.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A freshly issued token: the raw value goes to the browser, only its hash is kept. */
    public record Issued(UUID userId, String token, boolean persistent, Instant expiresAt) {}

    private final RefreshTokenRepository tokens;
    private final RefreshProperties props;

    public RefreshTokenService(RefreshTokenRepository tokens, RefreshProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    /** Starts a new login session (a new family) for {@code userId}. */
    @Transactional
    public Issued start(UUID userId, boolean persistent) {
        Duration ttl = persistent ? props.rememberTtl() : props.sessionTtl();
        return store(userId, UUID.randomUUID(), persistent, Instant.now().plus(ttl));
    }

    /**
     * Exchanges a presented refresh token for the next one in its chain. Empty if the token is unknown,
     * expired, revoked, or was already used (in which case the whole login session is revoked).
     */
    @Transactional
    public Optional<Issued> rotate(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        RefreshToken current = tokens.lockByTokenHash(hash(presented)).orElse(null);
        if (current == null || current.getRevokedAt() != null || !current.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        if (current.getUsedAt() == null) {
            current.markUsed(now);
        } else if (Duration.between(current.getUsedAt(), now).compareTo(props.reuseGrace()) > 0) {
            tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("A used refresh token was presented again: login session {} revoked", current.getFamilyId());
            return Optional.empty();
        } // else: two tabs refreshing together - answer the second one with a sibling token, no revocation
        return Optional.of(store(current.getUserId(), current.getFamilyId(), current.isPersistent(), current.getExpiresAt()));
    }

    /** Ends the login session the presented token belongs to. Unknown or already-ended tokens are ignored. */
    @Transactional
    public void end(String presented) {
        if (presented == null || presented.isBlank()) {
            return;
        }
        tokens.lockByTokenHash(hash(presented)).ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    /** Expired sessions are useless; keep them a day, then drop them so the table does not grow forever. */
    @Scheduled(fixedDelay = 3_600_000)
    void purgeExpired() {
        DbContext.callInScope(DbContext.SCOPE_AUTH, () -> tokens.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(1))));
    }

    private Issued store(UUID userId, UUID familyId, boolean persistent, Instant expiresAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new RefreshToken(userId, familyId, hash(raw), persistent, expiresAt));
        return new Issued(userId, raw, persistent, expiresAt);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
