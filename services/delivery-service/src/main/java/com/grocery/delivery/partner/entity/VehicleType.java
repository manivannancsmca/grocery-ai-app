package com.grocery.delivery.partner.entity;

public enum VehicleType {
    BICYCLE,
    SCOOTER,
    MOTORCYCLE,
    CAR;

    /** Motor vehicles need a registration number and a driving licence; bicycles need neither. */
    public boolean isMotorised() {
        return this != BICYCLE;
    }
}
