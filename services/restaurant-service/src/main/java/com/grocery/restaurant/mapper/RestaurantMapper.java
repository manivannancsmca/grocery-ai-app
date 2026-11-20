package com.grocery.restaurant.mapper;

import com.grocery.restaurant.dto.RestaurantDtos.AddressDto;
import com.grocery.restaurant.dto.RestaurantDtos.OpeningHoursDto;
import com.grocery.restaurant.dto.RestaurantDtos.RestaurantView;
import com.grocery.restaurant.dto.RestaurantDtos.StatusChangeView;
import com.grocery.restaurant.entity.BusinessAddress;
import com.grocery.restaurant.entity.OpeningHours;
import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.entity.RestaurantStatusChange;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

public final class RestaurantMapper {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private RestaurantMapper() {
    }

    public static RestaurantView toView(Restaurant r) {
        return new RestaurantView(r.getId(), r.getOwnerUserId(), r.getStatus().name(), r.getName(), r.getDescription(),
                r.getCuisines(), r.getFssaiLicense(), r.getGstin(), r.getContactEmail(), r.getContactPhone(),
                toDto(r.getAddress()), toDtos(r.getOpeningHours()), r.isAcceptingOrders(), r.getStatusReason(),
                r.getSubmittedAt(), r.getApprovedAt(), r.getCreatedAt(), r.getUpdatedAt());
    }

    /** Monday first, whatever order the rows come back in. */
    public static List<OpeningHours> sortedHours(List<OpeningHours> hours) {
        return hours.stream().sorted(Comparator.comparing(OpeningHours::getDayOfWeek)).toList();
    }

    /** "HH:mm", the format of every opening-hours time in requests, responses and events. */
    public static String formatTime(LocalTime time) {
        return HH_MM.format(time);
    }

    public static BusinessAddress toEntity(AddressDto dto) {
        if (dto == null) {
            return null;
        }
        return new BusinessAddress(trim(dto.line1()), trim(dto.line2()), trim(dto.city()), trim(dto.state()),
                trim(dto.postalCode()), dto.country() == null ? "IN" : dto.country(), dto.latitude(), dto.longitude());
    }

    /** Validated "HH:mm" entries → embeddables. Uniqueness per day is guaranteed by request validation. */
    public static List<OpeningHours> toEntities(List<OpeningHoursDto> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(h -> new OpeningHours(DayOfWeek.valueOf(h.dayOfWeek()), LocalTime.parse(h.opensAt(), HH_MM),
                        LocalTime.parse(h.closesAt(), HH_MM)))
                .toList();
    }

    /** Cuisine tokens in the owner's order, duplicates dropped. */
    public static List<String> toCuisines(List<String> cuisines) {
        return cuisines == null ? List.of() : cuisines.stream().distinct().toList();
    }

    public static StatusChangeView toView(RestaurantStatusChange c) {
        return new StatusChangeView(c.getFromStatus() == null ? null : c.getFromStatus().name(),
                c.getToStatus().name(), c.getReason(), c.getChangedBy(), c.getChangedAt());
    }

    private static AddressDto toDto(BusinessAddress a) {
        if (a == null) {
            return null;
        }
        return new AddressDto(a.getLine1(), a.getLine2(), a.getCity(), a.getState(), a.getPostalCode(),
                a.getCountry(), a.getLatitude(), a.getLongitude());
    }

    private static List<OpeningHoursDto> toDtos(List<OpeningHours> hours) {
        return sortedHours(hours).stream()
                .map(h -> new OpeningHoursDto(h.getDayOfWeek().name(), formatTime(h.getOpensAt()),
                        formatTime(h.getClosesAt())))
                .toList();
    }

    public static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
