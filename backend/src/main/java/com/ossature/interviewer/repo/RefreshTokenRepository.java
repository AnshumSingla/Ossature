package com.ossature.interviewer.repo;

import com.ossature.interviewer.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/* Read-only-transactional by default, like every repository (see UserRepository). */
@Transactional(readOnly = true)
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Locks the row so two requests presenting the same token are handled one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> lockByTokenHash(@Param("hash") String hash);

    @Transactional
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :family and t.revokedAt is null")
    int revokeFamily(@Param("family") UUID familyId, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
