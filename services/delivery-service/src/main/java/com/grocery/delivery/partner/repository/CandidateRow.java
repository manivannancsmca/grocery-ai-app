package com.grocery.delivery.partner.repository;

import java.util.UUID;

/** A wave candidate as read without a lock (scalar projection: nothing enters the persistence context). */
public record CandidateRow(UUID id, Double latitude, Double longitude) {
}
