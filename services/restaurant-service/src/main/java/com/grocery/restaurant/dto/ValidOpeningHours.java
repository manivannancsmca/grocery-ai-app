package com.grocery.restaurant.dto;

import com.grocery.restaurant.dto.RestaurantDtos.OpeningHoursDto;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rules across a weekly schedule: each day at most once, and {@code closesAt} must differ from
 * {@code opensAt} (closing before opening is allowed: the kitchen closes after midnight).
 * Errors point at the offending entry, e.g. {@code openingHours[2].closesAt}. Entry formats are
 * checked by {@link OpeningHoursDto}'s own constraints.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidOpeningHours.Validator.class)
public @interface ValidOpeningHours {

    String message() default "invalid opening hours";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidOpeningHours, List<OpeningHoursDto>> {

        @Override
        public boolean isValid(List<OpeningHoursDto> hours, ConstraintValidatorContext context) {
            if (hours == null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            boolean valid = true;
            Set<String> days = new HashSet<>();
            for (int i = 0; i < hours.size(); i++) {
                OpeningHoursDto entry = hours.get(i);
                if (entry == null) {
                    continue;
                }
                if (entry.dayOfWeek() != null && !days.add(entry.dayOfWeek())) {
                    valid = false;
                    report(context, i, "dayOfWeek", "is listed more than once");
                }
                if (entry.opensAt() != null && entry.opensAt().equals(entry.closesAt())) {
                    valid = false;
                    report(context, i, "closesAt", "must differ from opensAt");
                }
            }
            return valid;
        }

        // Constant messages only: user input never goes into a message template.
        private static void report(ConstraintValidatorContext context, int index, String field, String message) {
            context.buildConstraintViolationWithTemplate(message)
                    .addPropertyNode(field).inIterable().atIndex(index)
                    .addConstraintViolation();
        }
    }
}
