package com.ossature.interviewer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer <token>}, and on success sets the authenticated
 * principal to the user's {@link java.util.UUID} directly - controllers read it straight
 * off {@code Authentication#getPrincipal()}, no extra user lookup needed per request.
 *
 * <p>Also grants {@code ROLE_OWNER} when the token's email claim is in
 * {@code ossature.auth.owner-emails} (PRD 9 question-review workflow) - a config-driven
 * role rather than a DB column, since this app has exactly one kind of elevated user.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final OwnerPolicy ownerPolicy;

    public JwtAuthFilter(JwtService jwt, OwnerPolicy ownerPolicy) {
        this.jwt = jwt;
        this.ownerPolicy = ownerPolicy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwt.verifyClaims(header.substring(7)).ifPresent(claims -> {
                List<SimpleGrantedAuthority> authorities = ownerPolicy.isOwner(claims.email())
                        ? List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
                        : List.of();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(claims.userId(), null, authorities));
            });
        }
        chain.doFilter(request, response);
    }
}
