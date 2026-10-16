package com.ossature.interviewer.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT auth (PRD 6.2 follow-up). No sessions/cookies - every request carries its
 * own {@code Authorization: Bearer <token>}, validated by {@link JwtAuthFilter}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                // Picks up WebConfig's addCorsMappings via Spring MVC's HandlerMappingIntrospector -
                // no separate CorsConfigurationSource bean needed.
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        // Owner-only review workflow (PRD 9) - must come before the general
                        // GET permitAll below, since matcher order decides on first match.
                        .requestMatchers(HttpMethod.GET, "/api/questions/pending").hasRole("OWNER")
                        .requestMatchers(HttpMethod.POST, "/api/questions/*/approve", "/api/questions/*/reject")
                            .hasRole("OWNER")
                        // GET /api/questions* is public browsing; everything else needs a user.
                        .requestMatchers(HttpMethod.GET, "/api/questions/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Metrics etc. leak internals (and cost/usage numbers): owner only, not
                        // merely "any logged-in user" which is what anyRequest() would give them.
                        .requestMatchers("/actuator/**").hasRole("OWNER")
                        // Validation failures (e.g. @Valid on signup) internally forward to
                        // /error - without this, Security blocks that forward and the real
                        // 400 gets clobbered into an opaque 403.
                        .requestMatchers("/error").permitAll()
                        // The WS handshake authenticates itself (token query param, see
                        // InterviewWebSocketHandler) since browsers can't set a header on it.
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * JwtAuthFilter is a @Component, so Spring Boot would also register it as a plain servlet
     * filter - running it a second time outside the security chain, where Spring Security may
     * clear what it sets. It belongs only inside the chain (addFilterBefore above).
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
