package com.ossature.interviewer.web;

import com.ossature.interviewer.service.EmailAlreadyExistsException;
import com.ossature.interviewer.service.InvalidCredentialsException;
import com.ossature.interviewer.service.TooManyRequestsException;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setProperties(Map.of("timestamp", Instant.now().toString()));
        return pd;
    }

    /**
     * A constraint the database enforces was hit (for example a token that outlived its account). Never a bare
     * 500: say there is a conflict, and keep the SQL and constraint names out of the response.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ProblemDetail onDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "That request conflicts with existing data.");
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ProblemDetail onUnsupported(UnsupportedOperationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, ex.getMessage());
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail onEmailExists(EmailAlreadyExistsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(com.ossature.interviewer.service.AccountGoneException.class)
    public ProblemDetail onAccountGone(com.ossature.interviewer.service.AccountGoneException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail onInvalidCredentials(InvalidCredentialsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /** 403, not 401: the person IS signed in (a 401 would make the client try to refresh their session). */
    @ExceptionHandler(com.ossature.interviewer.service.WrongPasswordException.class)
    public ProblemDetail onWrongPassword(com.ossature.interviewer.service.WrongPasswordException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail onTooManyRequests(TooManyRequestsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    /** Surface the actual validation messages (e.g. the password rule) instead of Spring's generic "Invalid request content". */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onInvalidBody(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> capitalize(f.getField()) + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail.isEmpty() ? "Invalid request" : detail);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
