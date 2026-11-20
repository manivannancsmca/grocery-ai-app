package com.grocery.customer.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.customer.dto.CustomerDtos.AddressRequest;
import com.grocery.customer.dto.CustomerDtos.AddressView;
import com.grocery.customer.service.AddressBookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The signed-in customer's address book. {@code GET /{id}} is also how order-service (Phase 6) reads the
 * delivery address at checkout, forwarding the customer's own token.
 */
@RestController
@RequestMapping("/api/customers/me/addresses")
@PreAuthorize("hasRole('CUSTOMER')")
public class AddressController {

    private final AddressBookService addressBook;

    public AddressController(AddressBookService addressBook) {
        this.addressBook = addressBook;
    }

    @GetMapping
    public List<AddressView> list(CurrentUser user) {
        return addressBook.list(user);
    }

    @GetMapping("/{id}")
    public AddressView get(CurrentUser user, @PathVariable UUID id) {
        return addressBook.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressView add(CurrentUser user, @Valid @RequestBody AddressRequest request) {
        return addressBook.add(user, request);
    }

    @PutMapping("/{id}")
    public AddressView update(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody AddressRequest request) {
        return addressBook.update(user, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentUser user, @PathVariable UUID id) {
        addressBook.delete(user, id);
    }

    @PostMapping("/{id}/default")
    public AddressView makeDefault(CurrentUser user, @PathVariable UUID id) {
        return addressBook.makeDefault(user, id);
    }
}
