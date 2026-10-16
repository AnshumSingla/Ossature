package com.ossature.interviewer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ossature.interviewer.domain.User;
import com.ossature.interviewer.repo.UserRepository;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.security.OwnerPolicy;
import com.ossature.interviewer.web.dto.AuthDtos.LoginRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * H12: login must do one bcrypt comparison whether or not the account exists, so response time
 * can't reveal which emails are registered. (Response *text* was already identical.)
 */
class LoginConstantWorkTest {

    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private final AuthService auth = new AuthService(users, encoder, mock(JwtService.class), mock(OwnerPolicy.class));

    @Test
    void unknownEmailStillPerformsOneBcryptComparison() {
        when(users.findByEmail("ghost@x.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.login(new LoginRequest("ghost@x.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(encoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void accountWithoutAPasswordAlsoPerformsOneComparison() {
        when(users.findByEmail("old@x.com")).thenReturn(Optional.of(new User("old@x.com", "Old"))); // no hash

        assertThatThrownBy(() -> auth.login(new LoginRequest("old@x.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(encoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void wrongPasswordForARealAccountPerformsOneComparisonAndFails() {
        User user = new User("real@x.com", "Real");
        user.setPasswordHash(encoder.encode("Correct1pass"));
        when(users.findByEmail("real@x.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> auth.login(new LoginRequest("real@x.com", "Wrong1pass")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(encoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void theErrorIsIdenticalForUnknownAndWrongPassword() {
        User user = new User("real@x.com", "Real");
        user.setPasswordHash(encoder.encode("Correct1pass"));
        when(users.findByEmail("real@x.com")).thenReturn(Optional.of(user));
        when(users.findByEmail("ghost@x.com")).thenReturn(Optional.empty());

        String unknown = thrownMessage(() -> auth.login(new LoginRequest("ghost@x.com", "Wrong1pass")));
        String wrong = thrownMessage(() -> auth.login(new LoginRequest("real@x.com", "Wrong1pass")));
        assertThat(unknown).isEqualTo(wrong);
    }

    private static String thrownMessage(Runnable r) {
        try {
            r.run();
            return "no exception";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
