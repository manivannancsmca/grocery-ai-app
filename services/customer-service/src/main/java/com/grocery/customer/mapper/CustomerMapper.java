package com.grocery.customer.mapper;

import com.grocery.customer.dto.CustomerDtos.AddressView;
import com.grocery.customer.dto.CustomerDtos.CustomerProfile;
import com.grocery.customer.entity.Customer;
import com.grocery.customer.entity.CustomerAddress;

public final class CustomerMapper {

    private CustomerMapper() {
    }

    public static CustomerProfile toView(Customer c) {
        return new CustomerProfile(c.getId(), c.getEmail(), c.getFullName(), c.getPhone(), c.getCreatedAt(),
                c.getUpdatedAt());
    }

    public static AddressView toView(CustomerAddress a) {
        return new AddressView(a.getId(), a.getLabel(), a.getRecipientName(), a.getPhone(), a.getLine1(),
                a.getLine2(), a.getLandmark(), a.getCity(), a.getState(), a.getPostalCode(), a.getCountry(),
                a.getLatitude(), a.getLongitude(), a.isDefaultAddress(), a.getCreatedAt(), a.getUpdatedAt());
    }

    public static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
