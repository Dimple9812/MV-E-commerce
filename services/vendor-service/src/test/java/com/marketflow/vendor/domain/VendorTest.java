package com.marketflow.vendor.domain;

import com.marketflow.platform.web.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VendorTest {

    private Vendor newVendor() {
        return Vendor.apply(UUID.randomUUID(), "Acme Goods", "acme-goods", "desc", "Sales@Acme.test", null, "us");
    }

    @Test
    void startsPendingWithDefaultCommission() {
        Vendor v = newVendor();
        assertThat(v.getStatus()).isEqualTo(Vendor.Status.PENDING);
        assertThat(v.getCommissionRate()).isEqualByComparingTo("0.10");
        assertThat(v.getContactEmail()).isEqualTo("sales@acme.test");
        assertThat(v.getCountry()).isEqualTo("US");
        assertThat(v.isApproved()).isFalse();
    }

    @Test
    void followsLegalTransitionsOnly() {
        Vendor v = newVendor();
        UUID admin = UUID.randomUUID();
        assertThatThrownBy(() -> v.transition(Vendor.Status.SUSPENDED, admin, "x"))
                .isInstanceOf(ApiException.class);
        v.transition(Vendor.Status.APPROVED, admin, "ok");
        assertThat(v.isApproved()).isTrue();
        assertThatThrownBy(() -> v.transition(Vendor.Status.PENDING, admin, "x")).isInstanceOf(ApiException.class);
        v.transition(Vendor.Status.SUSPENDED, admin, "fraud check");
        assertThat(v.getStatusReason()).isEqualTo("fraud check");
        v.transition(Vendor.Status.APPROVED, admin, "cleared");
        assertThat(v.isApproved()).isTrue();
    }

    @Test
    void rejectedVendorCanReapply() {
        Vendor v = newVendor();
        v.transition(Vendor.Status.REJECTED, UUID.randomUUID(), "incomplete");
        v.transition(Vendor.Status.PENDING, v.getOwnerUserId(), "re-application");
        assertThat(v.getStatus()).isEqualTo(Vendor.Status.PENDING);
        assertThat(v.getDecidedAt()).isNull();
    }

    @Test
    void commissionIsBounded() {
        Vendor v = newVendor();
        v.changeCommission(new BigDecimal("0.125"));
        assertThat(v.getCommissionRate()).isEqualByComparingTo("0.1250");
        assertThatThrownBy(() -> v.changeCommission(new BigDecimal("0.51"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> v.changeCommission(new BigDecimal("-0.01"))).isInstanceOf(ApiException.class);
    }
}
