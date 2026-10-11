package com.marketflow.payment.api;

import com.marketflow.payment.application.PaymentService;
import com.marketflow.payment.application.PaymentStore;
import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.ApiException;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Validated
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentStore store;
    private final PaymentService payments;
    private final JdbcTemplate jdbc;

    public PaymentController(PaymentStore store, PaymentService payments, JdbcTemplate jdbc) {
        this.store = store;
        this.payments = payments;
        this.jdbc = jdbc;
    }

    /** No token, provider internals or lease details are exposed. */
    public record PaymentView(UUID id, UUID orderId, BigDecimal amount, String currency, String status,
                              String failureCode, BigDecimal refundedAmount, Instant authorizedAt, Instant capturedAt,
                              Instant createdAt, List<RefundView> refunds) {
    }

    public record RefundView(UUID id, BigDecimal amount, String status, String reason, Instant createdAt,
                             Instant completedAt) {
    }

    public record RefundRequest(@DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                @NotBlank @Size(max = 300) String reason) {
    }

    private PaymentView view(PaymentStore.Payment p) {
        return new PaymentView(p.id(), p.orderId(), p.amount(), p.currency(), p.status(), p.failureCode(),
                p.refundedAmount(), p.authorizedAt(), p.capturedAt(), p.createdAt(), store.refunds(p.id()).stream()
                .map(r -> new RefundView(r.id(), r.amount(), r.status(), r.reason(), r.createdAt(), r.completedAt()))
                .toList());
    }

    /** Owners and admins only; for anyone else the payment does not exist (404). */
    @GetMapping("/api/v1/payments/{paymentId}")
    public PaymentView get(@PathVariable UUID paymentId) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return store.byId(paymentId).filter(p -> me.isAdmin() || p.customerId().equals(me.userId())).map(this::view)
                .orElseThrow(() -> ApiException.notFound("Payment"));
    }

    @GetMapping("/api/v1/payments/by-order/{orderId}")
    public PaymentView byOrder(@PathVariable UUID orderId) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return store.byOrder(orderId, false).filter(p -> me.isAdmin() || p.customerId().equals(me.userId()))
                .map(this::view).orElseThrow(() -> ApiException.notFound("Payment"));
    }

    // ---------------- admin ----------------

    @GetMapping("/api/v1/admin/payments")
    @PreAuthorize("hasRole('ADMIN')")
    public PageView<PaymentView> list(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        List<PaymentStore.Payment> rows = status == null
                ? jdbc.query("SELECT * FROM payment ORDER BY created_at DESC LIMIT ? OFFSET ?",
                PaymentStoreMappers.PAYMENT, size, (long) page * size)
                : jdbc.query("SELECT * FROM payment WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                PaymentStoreMappers.PAYMENT, status, size, (long) page * size);
        Long total = status == null ? jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)
                : jdbc.queryForObject("SELECT count(*) FROM payment WHERE status = ?", Long.class, status);
        return PageView.of(rows.stream().map(this::view).toList(), page, size, total == null ? 0 : total);
    }

    @GetMapping("/api/v1/admin/payments/{paymentId}/attempts")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> attempts(@PathVariable UUID paymentId) {
        return jdbc.queryForList("SELECT operation, outcome, provider_reference, error_code, duration_ms, created_at "
                + "FROM payment_attempt WHERE payment_id = ? ORDER BY id", paymentId);
    }

    @GetMapping("/api/v1/admin/payments/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> summary() {
        return jdbc.queryForList("SELECT status, count(*) AS count, coalesce(sum(amount), 0) AS amount, "
                + "coalesce(sum(refunded_amount), 0) AS refunded FROM payment GROUP BY status ORDER BY status");
    }

    @PostMapping("/api/v1/payments/{paymentId}/refunds")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundView refund(@PathVariable UUID paymentId,
                             @RequestHeader(name = "Idempotency-Key") @Size(min = 8, max = 100) String key,
                             @Valid @RequestBody RefundRequest req) {
        PaymentStore.Payment p = store.byId(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        return payments.refund(p.orderId(), req.amount(), req.reason(), "admin:" + key,
                        AuthenticatedUser.current().userId(), null)
                .map(r -> new RefundView(r.id(), r.amount(), r.status(), r.reason(), r.createdAt(), r.completedAt()))
                .orElseThrow(() -> ApiException.unprocessable("not_refundable", "Payment is not refundable"));
    }
}
