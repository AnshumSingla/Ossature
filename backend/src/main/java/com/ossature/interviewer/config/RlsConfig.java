package com.ossature.interviewer.config;

import com.ossature.interviewer.db.RlsJpaTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Replaces Spring Boot's default JPA transaction manager with the one that applies the DB context (H17). */
@Configuration
public class RlsConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new RlsJpaTransactionManager(emf);
    }
}
