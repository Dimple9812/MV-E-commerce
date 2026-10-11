package com.marketflow.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_address")
public class Address {

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false)
    private String label;
    @Column(nullable = false)
    private String recipient;
    @Column(nullable = false)
    private String line1;
    private String line2;
    @Column(nullable = false)
    private String city;
    private String state;
    @Column(name = "postal_code", nullable = false)
    private String postalCode;
    @Column(nullable = false, length = 2)
    private String country;
    private String phone;
    @Column(name = "is_default", nullable = false)
    private boolean isDefault;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Address() {
    }

    public Address(UUID userId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public void update(String label, String recipient, String line1, String line2, String city, String state,
                       String postalCode, String country, String phone) {
        this.label = label;
        this.recipient = recipient;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = country.toUpperCase(java.util.Locale.ROOT);
        this.phone = phone;
    }

    public void setDefault(boolean value) {
        this.isDefault = value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getLabel() {
        return label;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isDefault() {
        return isDefault;
    }
}
