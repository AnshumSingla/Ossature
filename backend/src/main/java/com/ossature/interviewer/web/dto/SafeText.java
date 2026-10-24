package com.ossature.interviewer.web.dto;

import com.ossature.interviewer.service.TextSafety;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Text must not contain control characters, line separators or bidirectional overrides (see {@link TextSafety}). */
@Documented
@Constraint(validatedBy = SafeText.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeText {

    String message() default "must not contain control or invisible formatting characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<SafeText, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return TextSafety.isSafe(value);
        }
    }
}
