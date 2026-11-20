package com.grocery.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** An RS256 key pair used to sign access tokens; public halves are published at the JWKS endpoint. */
@Entity
@Table(name = "jwt_signing_key")
public class SigningKey {

    @Id
    @Column(length = 64)
    private String kid;

    @Column(name = "public_key_base64", nullable = false, columnDefinition = "TEXT")
    private String publicKeyBase64;

    @Column(name = "private_key_base64", nullable = false, columnDefinition = "TEXT")
    private String privateKeyBase64;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SigningKey() {
    }

    public SigningKey(String kid, String publicKeyBase64, String privateKeyBase64, Instant createdAt) {
        this.kid = kid;
        this.publicKeyBase64 = publicKeyBase64;
        this.privateKeyBase64 = privateKeyBase64;
        this.active = true;
        this.createdAt = createdAt;
    }

    public String getKid() {
        return kid;
    }

    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    public String getPrivateKeyBase64() {
        return privateKeyBase64;
    }
}
