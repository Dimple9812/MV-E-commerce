package com.marketflow.vendor.api;

import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.PageView;
import com.marketflow.vendor.api.VendorDtos.ApplicationRequest;
import com.marketflow.vendor.api.VendorDtos.DashboardView;
import com.marketflow.vendor.api.VendorDtos.EarningsEntryView;
import com.marketflow.vendor.api.VendorDtos.PayoutView;
import com.marketflow.vendor.api.VendorDtos.PublicVendorView;
import com.marketflow.vendor.api.VendorDtos.VendorView;
import com.marketflow.vendor.application.EarningsService;
import com.marketflow.vendor.application.VendorService;
import com.marketflow.vendor.domain.Vendor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@Tag(name = "Vendors")
public class VendorController {

    private final VendorService vendors;
    private final EarningsService earnings;

    public VendorController(VendorService vendors, EarningsService earnings) {
        this.vendors = vendors;
        this.earnings = earnings;
    }

    @PostMapping("/api/v1/vendors/applications")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit (or re-submit after rejection) a seller application for the calling user")
    public VendorView apply(@Valid @RequestBody ApplicationRequest req) {
        return VendorView.of(vendors.apply(AuthenticatedUser.current().userId(), toData(req)));
    }

    @GetMapping("/api/v1/vendors/me")
    @Operation(summary = "Application / account status of the calling user")
    public VendorView me() {
        return VendorView.of(vendors.mine(AuthenticatedUser.current().userId()));
    }

    @GetMapping("/api/v1/vendors/{vendorId}")
    @Operation(summary = "Public storefront profile (approved vendors only)")
    public PublicVendorView publicProfile(@PathVariable UUID vendorId) {
        return PublicVendorView.of(vendors.publicProfile(vendorId));
    }

    // ---------- vendor portal (approved vendors) ----------

    @PatchMapping("/api/v1/vendor/profile")
    @PreAuthorize("hasRole('VENDOR')")
    public VendorView updateProfile(@Valid @RequestBody ApplicationRequest req) {
        return VendorView.of(vendors.updateProfile(AuthenticatedUser.current().userId(), toData(req)));
    }

    @GetMapping("/api/v1/vendor/dashboard")
    @PreAuthorize("hasRole('VENDOR')")
    public DashboardView dashboard() {
        Vendor v = vendors.approvedVendorOf(AuthenticatedUser.current().userId());
        return new DashboardView(VendorView.of(v), earnings.summary(v));
    }

    @GetMapping("/api/v1/vendor/earnings")
    @PreAuthorize("hasRole('VENDOR')")
    public PageView<EarningsEntryView> earnings(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // the vendor is resolved from the token: a vendor can only ever see its own ledger
        Vendor v = vendors.approvedVendorOf(AuthenticatedUser.current().userId());
        return PageView.of(earnings.entries(v.getId(), PageRequest.of(page, size)), EarningsEntryView::of);
    }

    @GetMapping("/api/v1/vendor/earnings/payouts")
    @PreAuthorize("hasRole('VENDOR')")
    public List<PayoutView> payouts() {
        Vendor v = vendors.approvedVendorOf(AuthenticatedUser.current().userId());
        return earnings.payouts(v.getId()).stream().map(PayoutView::of).toList();
    }

    private static VendorService.ApplicationData toData(ApplicationRequest r) {
        return new VendorService.ApplicationData(r.businessName(), r.description(), r.contactEmail(), r.contactPhone(),
                r.country());
    }
}
