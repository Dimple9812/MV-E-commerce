package com.marketflow.vendor.api;

import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.PageView;
import com.marketflow.vendor.api.VendorDtos.ApproveRequest;
import com.marketflow.vendor.api.VendorDtos.CommissionRequest;
import com.marketflow.vendor.api.VendorDtos.DashboardView;
import com.marketflow.vendor.api.VendorDtos.DecisionRequest;
import com.marketflow.vendor.api.VendorDtos.HistoryView;
import com.marketflow.vendor.api.VendorDtos.PayoutRequest;
import com.marketflow.vendor.api.VendorDtos.PayoutView;
import com.marketflow.vendor.api.VendorDtos.VendorView;
import com.marketflow.vendor.application.EarningsService;
import com.marketflow.vendor.application.VendorService;
import com.marketflow.vendor.domain.Vendor;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin - vendors")
public class AdminVendorController {

    private final VendorService vendors;
    private final EarningsService earnings;

    public AdminVendorController(VendorService vendors, EarningsService earnings) {
        this.vendors = vendors;
        this.earnings = earnings;
    }

    @GetMapping("/api/v1/admin/vendors")
    public PageView<VendorView> list(@RequestParam(required = false) Vendor.Status status,
                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageView.of(vendors.list(status, PageRequest.of(page, size, Sort.by("submittedAt").descending())),
                VendorView::of);
    }

    @GetMapping("/api/v1/admin/vendors/pending")
    public PageView<VendorView> pending(@RequestParam(defaultValue = "0") @Min(0) int page,
                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageView.of(vendors.list(Vendor.Status.PENDING, PageRequest.of(page, size,
                Sort.by("submittedAt").ascending())), VendorView::of);
    }

    @GetMapping("/api/v1/admin/vendors/{vendorId}")
    public DashboardView get(@PathVariable UUID vendorId) {
        Vendor v = vendors.get(vendorId);
        return new DashboardView(VendorView.of(v), earnings.summary(v));
    }

    @GetMapping("/api/v1/admin/vendors/{vendorId}/history")
    public List<HistoryView> history(@PathVariable UUID vendorId) {
        return vendors.history(vendorId).stream().map(HistoryView::of).toList();
    }

    @PostMapping("/api/v1/admin/vendors/{vendorId}/approve")
    public VendorView approve(@PathVariable UUID vendorId, @Valid @RequestBody(required = false) ApproveRequest req) {
        return VendorView.of(vendors.approve(AuthenticatedUser.current().userId(), vendorId,
                req == null ? null : req.commissionRate()));
    }

    @PostMapping("/api/v1/admin/vendors/{vendorId}/reject")
    public VendorView reject(@PathVariable UUID vendorId, @Valid @RequestBody DecisionRequest req) {
        return VendorView.of(vendors.reject(AuthenticatedUser.current().userId(), vendorId, req.reason()));
    }

    @PostMapping("/api/v1/admin/vendors/{vendorId}/suspend")
    public VendorView suspend(@PathVariable UUID vendorId, @Valid @RequestBody DecisionRequest req) {
        return VendorView.of(vendors.suspend(AuthenticatedUser.current().userId(), vendorId, req.reason()));
    }

    @PostMapping("/api/v1/admin/vendors/{vendorId}/reinstate")
    public VendorView reinstate(@PathVariable UUID vendorId) {
        return VendorView.of(vendors.reinstate(AuthenticatedUser.current().userId(), vendorId));
    }

    @PutMapping("/api/v1/admin/vendors/{vendorId}/commission")
    public VendorView commission(@PathVariable UUID vendorId, @Valid @RequestBody CommissionRequest req) {
        return VendorView.of(vendors.changeCommission(AuthenticatedUser.current().userId(), vendorId,
                req.commissionRate()));
    }

    @PostMapping("/api/v1/admin/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    public PayoutView recordPayout(@Valid @RequestBody PayoutRequest req) {
        return PayoutView.of(earnings.recordPayout(AuthenticatedUser.current().userId(), req.vendorId(), req.amount(),
                req.reference()));
    }
}
