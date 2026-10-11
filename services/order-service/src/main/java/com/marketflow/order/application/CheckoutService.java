package com.marketflow.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.order.api.OrderDtos.CheckoutItem;
import com.marketflow.order.api.OrderDtos.CheckoutRequest;
import com.marketflow.order.config.OrderProperties;
import com.marketflow.order.domain.Order;
import com.marketflow.order.domain.pricing.PricingCalculator;
import com.marketflow.order.domain.saga.CheckoutSaga;
import com.marketflow.order.domain.saga.SagaState;
import com.marketflow.order.persistence.CatalogProjection;
import com.marketflow.order.persistence.OrderRepository;
import com.marketflow.order.persistence.SagaRepository;
import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Step 1-6 of the checkout saga, executed synchronously in ONE local transaction:
 * validate → revalidate against the catalog projection → price → persist order + suborders + saga → enqueue
 * {@code InventoryReservationRequested} in the outbox. The HTTP call returns 202 with the order ID; everything
 * after this runs asynchronously over Kafka.
 */
@Service
public class CheckoutService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9\\-_:.]{8,100}$");

    private final OrderRepository orders;
    private final SagaRepository sagas;
    private final CatalogProjection catalog;
    private final SagaSupport support;
    private final PricingCalculator pricing;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final int maxLines;

    public CheckoutService(OrderRepository orders, SagaRepository sagas, CatalogProjection catalog,
                           SagaSupport support, OrderProperties props, TransactionTemplate tx, ObjectMapper mapper) {
        this.orders = orders;
        this.sagas = sagas;
        this.catalog = catalog;
        this.support = support;
        this.pricing = new PricingCalculator(props.pricing().toPolicy());
        this.tx = tx;
        this.mapper = mapper;
        this.maxLines = props.maxLinesPerOrder();
    }

    public record Result(UUID orderId, boolean replayed) {
    }

    public Result checkout(AuthenticatedUser customer, String idempotencyKey, CheckoutRequest req) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("invalid_idempotency_key",
                    "Idempotency-Key header is required (8-100 characters of A-Z a-z 0-9 - _ : .)");
        }
        String hash = requestHash(req);
        try {
            return tx.execute(status -> {
                var existing = orders.findByCustomerIdAndIdempotencyKey(customer.userId(), idempotencyKey);
                if (existing.isPresent()) {
                    return replay(existing.get(), hash);
                }
                return create(customer, idempotencyKey, hash, req);
            });
        } catch (DataIntegrityViolationException race) {
            // a concurrent request with the same key committed first: return that order
            return tx.execute(status -> orders.findByCustomerIdAndIdempotencyKey(customer.userId(), idempotencyKey)
                    .map(o -> replay(o, hash)).orElseThrow(() -> race));
        }
    }

    private Result replay(Order existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw ApiException.unprocessable("idempotency_key_reused",
                    "This Idempotency-Key was already used for a different checkout request");
        }
        support.meters().counter("checkout.requests", "outcome", "replayed").increment();
        return new Result(existing.getId(), true);
    }

    private Result create(AuthenticatedUser customer, String key, String hash, CheckoutRequest req) {
        List<PricingCalculator.Line> lines = revalidate(req.items());
        PricingCalculator.Quote quote = pricing.price(lines, req.couponCode());
        Order order = Order.place(customer.userId(), customer.email(), key, hash, req.shippingAddress().toMap(),
                "USD", quote);
        orders.saveAndFlush(order); // unique (customer_id, idempotency_key) is enforced here

        CheckoutSaga saga = CheckoutSaga.start(order.getId(), req.paymentToken());
        sagas.save(saga);
        support.orderStatus(order, null, "checkout", customer.userId());
        support.move(saga, SagaState.INVENTORY_PENDING, "CheckoutSubmitted", null,
                quote.suborders().size() + " vendor suborder(s), total " + quote.grandTotal());
        support.orderEvent(order, "OrderCreated", null, false, null);
        support.inventoryCommand(order, "InventoryReservationRequested", null, null);
        support.meters().counter("checkout.requests", "outcome", "created").increment();
        return new Result(order.getId(), false);
    }

    /**
     * Re-checks every line against the locally projected catalog: the variant must exist and be active, the
     * product must be PUBLISHED, its vendor APPROVED, and per-product quantity within the purchase limit.
     * The unit price always comes from the projection, never from the client.
     */
    private List<PricingCalculator.Line> revalidate(List<CheckoutItem> items) {
        if (items.size() > maxLines) {
            throw ApiException.unprocessable("too_many_lines", "At most " + maxLines + " lines per order");
        }
        Map<UUID, Integer> qty = new LinkedHashMap<>();
        items.forEach(i -> qty.merge(i.variantId(), i.quantity(), Integer::sum));
        Map<UUID, CatalogProjection.Variant> found = catalog.variants(new ArrayList<>(qty.keySet()));
        Map<UUID, Integer> perProduct = new LinkedHashMap<>();
        List<PricingCalculator.Line> lines = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : qty.entrySet()) {
            CatalogProjection.Variant v = found.get(e.getKey());
            if (v == null || !v.active() || !"PUBLISHED".equals(v.productStatus())) {
                throw ApiException.unprocessable("item_unavailable",
                        "Item " + (v == null ? e.getKey() : v.sku()) + " is not available for purchase");
            }
            if (!"APPROVED".equals(v.vendorStatus()) || v.commissionRate() == null) {
                throw ApiException.unprocessable("vendor_unavailable",
                        "The seller of " + v.sku() + " is not accepting orders");
            }
            if (!"USD".equals(v.currency())) {
                throw ApiException.unprocessable("unsupported_currency", "Only USD is supported");
            }
            int productQty = perProduct.merge(v.productId(), e.getValue(), Integer::sum);
            if (productQty > v.maxPerOrder()) {
                throw ApiException.unprocessable("purchase_limit",
                        "At most " + v.maxPerOrder() + " units of " + v.productName() + " per order");
            }
            lines.add(new PricingCalculator.Line(v.productId(), v.variantId(), v.vendorId(), v.vendorName(),
                    v.commissionRate(), v.sku(), v.productName(), v.variantName(), v.imageUrl(), v.price(),
                    e.getValue()));
        }
        return lines;
    }

    /** Canonical hash of the request (items sorted) used to detect Idempotency-Key reuse with a different body. */
    String requestHash(CheckoutRequest req) {
        try {
            var canonical = new LinkedHashMap<String, Object>();
            canonical.put("items", req.items().stream().sorted(Comparator.comparing(CheckoutItem::variantId)
                    .thenComparing(CheckoutItem::quantity)).map(i -> i.variantId() + "x" + i.quantity()).toList());
            canonical.put("address", req.shippingAddress().toMap());
            canonical.put("coupon", req.couponCode() == null ? "" : req.couponCode().trim().toUpperCase());
            canonical.put("token", req.paymentToken());
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
