package com.ossature.interviewer.service;

import com.ossature.interviewer.domain.User;
import com.ossature.interviewer.repo.UserRepository;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.security.OwnerPolicy;
import com.ossature.interviewer.web.dto.AuthDtos.AuthResponse;
import com.ossature.interviewer.web.dto.AuthDtos.LoginRequest;
import com.ossature.interviewer.web.dto.AuthDtos.SignupRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final OwnerPolicy ownerPolicy;
    /**
     * A real bcrypt hash of a random value, computed once at the same cost as real ones. Login
     * checks the supplied password against it when the account doesn't exist, so "unknown
     * email" takes as long as "wrong password" and response time can't be used to discover
     * which emails are registered.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, OwnerPolicy ownerPolicy) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.ownerPolicy = ownerPolicy;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        String email = Emails.normalize(req.email());
        if (users.findByEmail(email).isPresent()) {
            throw new EmailAlreadyExistsException(email);
        }
        User user = new User(email, req.displayName().trim());
        user.setPasswordHash(encoder.encode(req.password()));
        try {
            // saveAndFlush so a concurrent signup for the same address fails here, as a clean
            // "already exists", instead of later at commit as an opaque 500.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyExistsException(email);
        }
        return respond(user);
    }

    public AuthResponse login(LoginRequest req) {
        User account = users.findByEmail(Emails.normalize(req.email()))
                .filter(u -> u.getPasswordHash() != null)
                .orElse(null);
        // Always do exactly one bcrypt comparison, whether or not the account exists.
        boolean matches = encoder.matches(req.password(), account != null ? account.getPasswordHash() : dummyHash);
        if (account == null || !matches) {
            throw new InvalidCredentialsException();
        }
        return respond(account);
    }

    /** A fresh access token for an existing login session (the refresh flow); empty if the account is gone. */
    @Transactional(readOnly = true)
    public java.util.Optional<AuthResponse> sessionFor(java.util.UUID userId) {
        return users.findById(userId).map(this::respond);
    }

    private AuthResponse respond(User user) {
        return AuthResponse.of(jwt.issue(user.getId(), user.getEmail()), jwt.expirySeconds(), user,
                ownerPolicy.isOwner(user.getEmail()));
    }
}
