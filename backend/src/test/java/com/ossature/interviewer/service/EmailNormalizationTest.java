package com.ossature.interviewer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ossature.interviewer.domain.User;
import com.ossature.interviewer.repo.UserRepository;
import com.ossature.interviewer.security.JwtService;
import com.ossature.interviewer.security.OwnerPolicy;
import com.ossature.interviewer.web.dto.AuthDtos.LoginRequest;
import com.ossature.interviewer.web.dto.AuthDtos.SignupRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** H2 / H1 interim mitigation: a case variant of an existing email must never become a new account. */
class EmailNormalizationTest {

    private UserRepository users;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        auth = new AuthService(users, new BCryptPasswordEncoder(4), mock(JwtService.class), mock(OwnerPolicy.class));
    }

    @Test
    void signupLooksUpAndStoresTheLowerCasedEmail() {
        when(users.findByEmail("owner@x.com")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));
        try {
            auth.signup(new SignupRequest("  Owner@X.com ", "Passw0rdOk", "Alice"));
        } catch (RuntimeException ignoredBecauseJwtIsAMock) {
            // token issuing is not under test here
        }
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("owner@x.com");
    }

    @Test
    void signupRejectsACaseVariantOfAnExistingAccount() {
        when(users.findByEmail("owner@x.com")).thenReturn(Optional.of(new User("owner@x.com", "Real Owner")));

        assertThatThrownBy(() -> auth.signup(new SignupRequest("OWNER@X.COM", "Passw0rdOk", "Mallory")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void aRaceOnTheUniqueIndexIsReportedAsAlreadyExistsNotAServerError() {
        when(users.findByEmail("a@x.com")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> auth.signup(new SignupRequest("A@x.com", "Passw0rdOk", "A")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() {
        when(users.findByEmail("a@x.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.login(new LoginRequest("A@X.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(users).findByEmail("a@x.com");
    }

    @Test
    void normalizeTrimsAndLowerCases() {
        assertThat(Emails.normalize("  MiXed@Case.COM ")).isEqualTo("mixed@case.com");
        assertThat(Emails.normalize(null)).isNull();
    }
}
