package com.ossature.interviewer.repo;

import org.springframework.transaction.annotation.Transactional;
import com.ossature.interviewer.domain.Question;
import com.ossature.interviewer.domain.QuestionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/*
 * Every repository is read-only-transactional by default: Spring Data runs derived query methods
 * (findByEmail, findByState, ...) OUTSIDE any transaction otherwise, and row-level security needs a
 * transaction to attach the acting user to (see db/RlsJpaTransactionManager). Without one the query
 * would run with no identity and see nothing. Methods inherited from JpaRepository (save, ...) keep
 * their own read-write transactions.
 */
@Transactional(readOnly = true)
public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByStatus(QuestionStatus status);

    Optional<Question> findBySlug(String slug);
}
