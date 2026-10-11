package com.marketflow.inventory.api;

import com.marketflow.inventory.application.StockService;
import com.marketflow.inventory.application.StockService.Availability;
import com.marketflow.inventory.application.StockService.InvariantViolation;
import com.marketflow.inventory.application.StockService.ItemView;
import com.marketflow.inventory.application.StockService.MovementView;
import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.ApiException;
import com.marketflow.platform.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@Tag(name = "Inventory")
public class InventoryController {

    private final StockService stock;

    public InventoryController(StockService stock) {
        this.stock = stock;
    }

    public record AdjustmentRequest(@NotNull UUID variantId,
                                    @NotNull @Min(-100000) @Max(100000) Integer delta,
                                    @NotBlank @Size(max = 300) String reason) {
    }

    public record ThresholdRequest(@NotNull @Min(0) @Max(100000) Integer threshold) {
    }

    @GetMapping("/api/v1/inventory/availability")
    @Operation(summary = "Public availability for up to 50 variants (cart / product page)")
    public List<Availability> availability(@RequestParam List<UUID> variantIds) {
        if (variantIds.size() > 50) {
            throw ApiException.badRequest("too_many_ids", "At most 50 variant IDs per request");
        }
        return stock.availability(variantIds);
    }

    @GetMapping("/api/v1/vendor/inventory")
    @PreAuthorize("hasRole('VENDOR')")
    public PageView<ItemView> items(@RequestParam(defaultValue = "false") boolean lowStockOnly,
                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                    @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        UUID vendor = stock.vendorOf(AuthenticatedUser.current().userId());
        return PageView.of(stock.items(vendor, lowStockOnly, page, size), page, size, stock.count(vendor, lowStockOnly));
    }

    @GetMapping("/api/v1/vendor/inventory/low-stock")
    @PreAuthorize("hasRole('VENDOR')")
    public List<ItemView> lowStock() {
        UUID vendor = stock.vendorOf(AuthenticatedUser.current().userId());
        return stock.items(vendor, true, 0, 200);
    }

    @PostMapping("/api/v1/vendor/inventory/adjustments")
    @PreAuthorize("hasRole('VENDOR')")
    public ItemView adjust(@Valid @RequestBody AdjustmentRequest req) {
        UUID user = AuthenticatedUser.current().userId();
        return stock.adjust(user, stock.vendorOf(user), req.variantId(), req.delta(), req.reason());
    }

    @PutMapping("/api/v1/vendor/inventory/{variantId}/threshold")
    @PreAuthorize("hasRole('VENDOR')")
    public ItemView threshold(@PathVariable UUID variantId, @Valid @RequestBody ThresholdRequest req) {
        return stock.setThreshold(stock.vendorOf(AuthenticatedUser.current().userId()), variantId, req.threshold());
    }

    @GetMapping("/api/v1/vendor/inventory/{variantId}/movements")
    @PreAuthorize("hasRole('VENDOR')")
    public List<MovementView> movements(@PathVariable UUID variantId) {
        return stock.movements(stock.vendorOf(AuthenticatedUser.current().userId()), variantId);
    }

    @GetMapping("/api/v1/admin/inventory/alerts")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ItemView> alerts(@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return stock.lowStockAcrossPlatform(limit);
    }

    @GetMapping("/api/v1/admin/inventory/ledger-check")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lists items whose quantities do not equal the sum of their stock movements (should be empty)")
    public List<InvariantViolation> ledgerCheck() {
        return stock.verifyLedger();
    }
}
