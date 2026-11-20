package com.grocery.delivery.partner.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

/**
 * Like {@code @Pattern}, but checked against the value as it will be stored
 * ({@link PartnerDtos#normalizeCode}: spaces and hyphens removed, upper case), so riders may type
 * "ka-01-ab 1234". {@code null} is valid; an empty string is not (same as {@code @Pattern}).
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NormalizedPattern.Validator.class)
public @interface NormalizedPattern {

    String regexp();

    String message() default "has an invalid format";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NormalizedPattern, String> {

        private Pattern pattern;

        @Override
        public void initialize(NormalizedPattern annotation) {
            pattern = Pattern.compile(annotation.regexp());
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || pattern.matcher(PartnerDtos.normalizeCode(value)).matches();
        }
    }
}
