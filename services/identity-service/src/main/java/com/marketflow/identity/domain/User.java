package com.marketflow.identity.domain;

import com.marketflow.platform.security.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class User {

    public enum Status { ACTIVE, DEACTIVATED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version; // null until first persist, so Spring Data treats assigned-ID entities as new

    protected User() {
    }

    /** Every self-registered account starts as CUSTOMER. VENDOR is only granted after admin approval. */
    public static User register(String email, String passwordHash, String fullName) {
        User u = new User();
        u.id = UUID.randomUUID();
        u.email = normalizeEmail(email);
        u.passwordHash = passwordHash;
        u.fullName = fullName.trim();
        u.roles.add(Role.CUSTOMER);
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        return u;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** Records a failed login and locks the account for {@code lockDuration} after {@code maxFailures}. */
    public void recordFailedLogin(int maxFailures, Duration lockDuration, Instant now) {
        failedLoginCount++;
        if (failedLoginCount >= maxFailures) {
            lockedUntil = now.plus(lockDuration);
            failedLoginCount = 0;
        }
        touch();
    }

    public void clearLockout() {
        failedLoginCount = 0;
        lockedUntil = null;
        touch();
    }

    public void recordSuccessfulLogin() {
        if (failedLoginCount != 0 || lockedUntil != null) {
            failedLoginCount = 0;
            lockedUntil = null;
            touch();
        }
    }

    public boolean grant(Role role) {
        boolean changed = roles.add(role);
        if (changed) {
            touch();
        }
        return changed;
    }

    public boolean revoke(Role role) {
        boolean changed = roles.remove(role);
        if (changed) {
            touch();
        }
        return changed;
    }

    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        touch();
    }

    public void rename(String fullName) {
        this.fullName = fullName.trim();
        touch();
    }

    public void deactivate() {
        this.status = Status.DEACTIVATED;
        touch();
    }

    public void activate() {
        this.status = Status.ACTIVE;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Status getStatus() {
        return status;
    }

    public Set<Role> getRoles() {
        return roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
