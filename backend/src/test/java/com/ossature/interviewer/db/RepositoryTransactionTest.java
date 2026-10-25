package com.ossature.interviewer.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * H17 guard: row-level security attaches the acting user at the start of a Spring transaction. Spring
 * Data runs a repository's derived query methods outside any transaction unless the repository is
 * annotated, and a query with no transaction has no identity - it would silently see nothing (this
 * already broke login and the scheduler once). Every repository must therefore be transactional.
 */
class RepositoryTransactionTest {

    @Test
    void everyRepositoryInterfaceIsTransactional() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition def) {
                return def.getMetadata().isInterface(); // the default skips interfaces
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

        Set<BeanDefinition> found = scanner.findCandidateComponents("com.ossature.interviewer.repo");
        assertThat(found).as("repositories found").isNotEmpty();

        List<String> missing = found.stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(name -> {
                    try {
                        return Class.forName(name).getAnnotation(Transactional.class) == null;
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(Collectors.toList());

        assertThat(missing)
                .as("repositories without @Transactional (their query methods would run with no database identity)")
                .isEmpty();
    }
}
