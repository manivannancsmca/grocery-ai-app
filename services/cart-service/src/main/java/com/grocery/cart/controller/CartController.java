package com.grocery.cart.controller;

import com.grocery.cart.dto.CartDtos.AddItemRequest;
import com.grocery.cart.dto.CartDtos.CartView;
import com.grocery.cart.dto.CartDtos.CheckoutPreview;
import com.grocery.cart.dto.CartDtos.CouponRequest;
import com.grocery.cart.dto.CartDtos.QuantityRequest;
import com.grocery.cart.service.CartService;
import com.grocery.common.security.CurrentUser;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The customer's own cart (contract §2, §3). customerId = the JWT subject; there is no way to address another cart. */
@RestController
@RequestMapping("/api/cart")
@PreAuthorize("hasRole('CUSTOMER')")
public class CartController {

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    @GetMapping
    public CartView get(CurrentUser user) {
        return carts.get(user.userId());
    }

    @PostMapping("/items")
    public CartView add(CurrentUser user, @Valid @RequestBody AddItemRequest request) {
        return carts.add(user.userId(), request);
    }

    @PutMapping("/items/{itemId}")
    public CartView update(CurrentUser user, @PathVariable UUID itemId, @Valid @RequestBody QuantityRequest request) {
        return carts.updateQuantity(user.userId(), itemId, request.quantity());
    }

    @DeleteMapping("/items/{itemId}")
    public CartView remove(CurrentUser user, @PathVariable UUID itemId) {
        return carts.remove(user.userId(), itemId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(CurrentUser user) {
        carts.clear(user.userId());
    }

    @GetMapping("/preview")
    public CheckoutPreview preview(CurrentUser user, @RequestParam(required = false) UUID addressId) {
        return carts.preview(user.userId(), addressId);
    }

    /** Phase 12 §2: applies the cart's one coupon (422 COUPON_NOT_APPLICABLE with {@code reason} when it can't). */
    @PutMapping("/coupon")
    public CartView applyCoupon(CurrentUser user, @Valid @RequestBody CouponRequest request) {
        return carts.applyCoupon(user.userId(), request.code());
    }

    /** Phase 12 §2: removes the coupon (idempotent). */
    @DeleteMapping("/coupon")
    public CartView removeCoupon(CurrentUser user) {
        return carts.removeCoupon(user.userId());
    }
}
