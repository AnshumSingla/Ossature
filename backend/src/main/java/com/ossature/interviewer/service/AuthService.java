package com.ossature.interviewer.service;

import com.ossature.interviewer.domain.User;
import com.ossature.interviewer.repo.UserRepository;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.web.dto.AuthDtos.AuthResponse;
import com.ossature.interviewer.web.dto.AuthDtos.LoginRequest;
import com.ossature.interviewer.web.dto.AuthDtos.SignupRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        if (users.findByEmail(req.email()).isPresent()) {
            throw new EmailAlreadyExistsException(req.email());
        }
        User user = new User(req.email(), req.displayName());
        user.setPasswordHash(encoder.encode(req.password()));
        users.save(user);
        return AuthResponse.of(jwt.issue(user.getId(), user.getEmail()), user);
    }

    public AuthResponse login(LoginRequest req) {
        User user = users.findByEmail(req.email())
                .filter(u -> u.getPasswordHash() != null)
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(InvalidCredentialsException::new);
        return AuthResponse.of(jwt.issue(user.getId(), user.getEmail()), user);
    }
}
