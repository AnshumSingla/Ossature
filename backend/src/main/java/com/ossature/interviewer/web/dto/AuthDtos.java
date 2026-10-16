package com.ossature.interviewer.web.dto;

import com.ossature.interviewer.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            // 8-72 chars (BCrypt silently ignores anything past 72 bytes), at least one
            // letter and one digit - a floor against "password123"-grade single-class input,
            // not full complexity scoring.
            @NotBlank @Size(min = 8, max = 72)
            @Pattern(regexp = "(?=.*[A-Za-z])(?=.*\\d).+",
                    message = "Password must contain at least one letter and one digit")
            String password,
            @NotBlank @Size(max = 80) String displayName) {}

    // Caps: an unbounded field is free memory/CPU for an attacker (bcrypt hashes whatever it is given).
    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 128) String password) {}

    public record AuthResponse(String token, UUID userId, String email, String displayName, boolean isOwner) {
        public static AuthResponse of(String token, User u, boolean isOwner) {
            return new AuthResponse(token, u.getId(), u.getEmail(), u.getDisplayName(), isOwner);
        }
    }
}
