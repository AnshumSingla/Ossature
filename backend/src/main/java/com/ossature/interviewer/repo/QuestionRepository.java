package com.ossature.interviewer.repo;

import com.ossature.interviewer.domain.Question;
import com.ossature.interviewer.domain.QuestionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByStatus(QuestionStatus status);

    Optional<Question> findBySlug(String slug);
}
