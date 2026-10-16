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

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, OwnerPolicy ownerPolicy) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.ownerPolicy = ownerPolicy;
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
        return AuthResponse.of(jwt.issue(user.getId(), user.getEmail()), user, ownerPolicy.isOwner(user.getEmail()));
    }

    public AuthResponse login(LoginRequest req) {
        User user = users.findByEmail(Emails.normalize(req.email()))
                .filter(u -> u.getPasswordHash() != null)
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(InvalidCredentialsException::new);
        return AuthResponse.of(jwt.issue(user.getId(), user.getEmail()), user, ownerPolicy.isOwner(user.getEmail()));
    }
}
