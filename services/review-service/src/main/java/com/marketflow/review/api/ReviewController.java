package com.marketflow.review.api;

import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.review.application.ReviewService;
import com.marketflow.review.application.ReviewService.Eligibility;
import com.marketflow.review.application.ReviewService.Rating;
import com.marketflow.review.application.ReviewService.Review;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
@Tag(name = "Reviews")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    public record ReviewRequest(@NotNull @Min(1) @Max(5) Integer rating,
                                @NotBlank @Size(min = 3, max = 120) String title,
                                @NotBlank @Size(min = 10, max = 4000) String body) {
    }

    public record ModerationRequest(@Size(max = 300) String note) {
    }

    /** Public view: customer IDs are not exposed. */
    public record PublicReview(UUID id, String authorName, int rating, String title, String body,
                               boolean verifiedPurchase, Instant createdAt, Instant updatedAt) {
        static PublicReview of(Review r) {
            return new PublicReview(r.id(), r.authorName(), r.rating(), r.title(), r.body(), r.verifiedPurchase(),
                    r.createdAt(), r.updatedAt());
        }
    }

    @GetMapping("/api/v1/products/{productId}/reviews")
    public List<PublicReview> list(@PathVariable UUID productId,
                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return reviews.published(productId, page, size).stream().map(PublicReview::of).toList();
    }

    @GetMapping("/api/v1/products/{productId}/rating")
    public Rating rating(@PathVariable UUID productId) {
        return reviews.rating(productId);
    }

    @GetMapping("/api/v1/products/{productId}/reviews/eligibility")
    public Eligibility eligibility(@PathVariable UUID productId) {
        return reviews.eligibility(AuthenticatedUser.current().userId(), productId);
    }

    @PostMapping("/api/v1/products/{productId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public Review create(@PathVariable UUID productId, @Valid @RequestBody ReviewRequest req) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return reviews.create(me.userId(), displayName(), productId, req.rating(), req.title(), req.body());
    }

    @GetMapping("/api/v1/reviews/me")
    public List<Review> mine() {
        return reviews.mine(AuthenticatedUser.current().userId());
    }

    @PutMapping("/api/v1/reviews/{reviewId}")
    public Review update(@PathVariable UUID reviewId, @Valid @RequestBody ReviewRequest req) {
        return reviews.update(AuthenticatedUser.current().userId(), reviewId, req.rating(), req.title(), req.body());
    }

    @DeleteMapping("/api/v1/reviews/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID reviewId) {
        reviews.delete(AuthenticatedUser.current().userId(), reviewId);
    }

    @GetMapping("/api/v1/admin/reviews")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Review> moderationQueue(@RequestParam(defaultValue = "PUBLISHED")
                                        @Pattern(regexp = "PUBLISHED|HIDDEN") String status,
                                        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return reviews.byStatus(status, limit);
    }

    @PostMapping("/api/v1/admin/reviews/{reviewId}/hide")
    @PreAuthorize("hasRole('ADMIN')")
    public Review hide(@PathVariable UUID reviewId, @Valid @RequestBody(required = false) ModerationRequest req) {
        return reviews.moderate(AuthenticatedUser.current().userId(), reviewId, true, req == null ? null : req.note());
    }

    @PostMapping("/api/v1/admin/reviews/{reviewId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public Review restore(@PathVariable UUID reviewId) {
        return reviews.moderate(AuthenticatedUser.current().userId(), reviewId, false, null);
    }

    /** Display name comes from the verified token ("name" claim); first name + initial for privacy. */
    private static String displayName() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t) {
            String name = t.getToken().getClaimAsString("name");
            if (name != null && !name.isBlank()) {
                String[] parts = name.trim().split("\\s+");
                return parts.length > 1 ? parts[0] + " " + parts[parts.length - 1].charAt(0) + "." : parts[0];
            }
        }
        return "Verified buyer";
    }
}
