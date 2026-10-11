package com.marketflow.identity.domain;

import com.marketflow.platform.security.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void newUsersAreCustomersOnlyWithNormalizedEmail() {
        User u = User.register("  Alice@Example.COM ", "hash", " Alice ");
        assertThat(u.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(u.getEmail()).isEqualTo("alice@example.com");
        assertThat(u.getFullName()).isEqualTo("Alice");
        assertThat(u.isActive()).isTrue();
    }

    @Test
    void locksAfterMaxFailuresAndUnlocksAfterDuration() {
        User u = User.register("a@b.co", "h", "A B");
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        for (int i = 0; i < 4; i++) {
            u.recordFailedLogin(5, Duration.ofMinutes(15), now);
        }
        assertThat(u.isLocked(now)).isFalse();
        u.recordFailedLogin(5, Duration.ofMinutes(15), now);
        assertThat(u.isLocked(now)).isTrue();
        assertThat(u.isLocked(now.plus(Duration.ofMinutes(16)))).isFalse();
    }

    @Test
    void successfulLoginResetsCounter() {
        User u = User.register("a@b.co", "h", "A B");
        Instant now = Instant.now();
        u.recordFailedLogin(5, Duration.ofMinutes(15), now);
        u.recordSuccessfulLogin();
        for (int i = 0; i < 4; i++) {
            u.recordFailedLogin(5, Duration.ofMinutes(15), now);
        }
        assertThat(u.isLocked(now)).isFalse();
    }

    @Test
    void grantAndRevokeReportChanges() {
        User u = User.register("a@b.co", "h", "A B");
        assertThat(u.grant(Role.VENDOR)).isTrue();
        assertThat(u.grant(Role.VENDOR)).isFalse();
        assertThat(u.revoke(Role.VENDOR)).isTrue();
        assertThat(u.revoke(Role.VENDOR)).isFalse();
    }
}
