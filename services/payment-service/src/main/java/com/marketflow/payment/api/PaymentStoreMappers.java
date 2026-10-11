package com.marketflow.payment.api;

import com.marketflow.payment.application.PaymentStore;
import org.springframework.jdbc.core.RowMapper;

final class PaymentStoreMappers {

    static final RowMapper<PaymentStore.Payment> PAYMENT = PaymentStore.mapper();

    private PaymentStoreMappers() {
    }
}
