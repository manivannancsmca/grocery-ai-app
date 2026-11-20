package com.grocery.payment.entity;

/** The state of one Razorpay payment (an attempt to pay a payment's Razorpay order). */
public enum AttemptStatus {
    AUTHORIZED, CAPTURED, FAILED
}
