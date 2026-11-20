package com.grocery.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A brand shared by all vendors. Brands are matched by {@link #nameKey(String)}, so "farm  fresh" and
 * "Farm Fresh" are the same brand; the first spelling wins as the display name. Brands are immutable.
 */
@Entity
@Table(name = "brand")
public class Brand {

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "name_key", nullable = false, unique = true, length = 80)
    private String nameKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Brand() {
    }

    public Brand(UUID id, String name, Instant now) {
        this.id = id;
        this.name = displayName(name);
        this.nameKey = nameKey(name);
        this.createdAt = now;
    }

    /** Trimmed, inner whitespace collapsed to one space. */
    public static String displayName(String name) {
        return name.trim().replaceAll("\\s+", " ");
    }

    /**
     * Matching key: lower-case, accents removed, whitespace collapsed, only {@code [a-z0-9 ]} kept.
     * Names without any such character (e.g. only non-Latin script) fall back to the lower-cased name.
     */
    public static String nameKey(String name) {
        String lower = displayName(name).toLowerCase(Locale.ROOT);
        String key = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]", "")
                .replaceAll(" +", " ")
                .trim();
        return key.isEmpty() ? lower : key;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }
}
