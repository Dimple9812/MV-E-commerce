package com.marketflow.identity.api;

import com.marketflow.identity.domain.Address;
import com.marketflow.identity.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public API models. JPA entities are never serialized directly. */
public final class Dtos {

    private Dtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(min = 2, max = 120) String fullName) {
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 72) String password) {
    }

    public record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record PasswordResetConfirm(@NotBlank @Size(max = 100) String token,
                                       @NotBlank @Size(min = 10, max = 72) String newPassword) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 72) String currentPassword,
                                        @NotBlank @Size(min = 10, max = 72) String newPassword) {
    }

    public record UpdateProfileRequest(@NotBlank @Size(min = 2, max = 120) String fullName) {
    }

    public record UserView(UUID id, String email, String fullName, List<String> roles, String status,
                           Instant createdAt) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getEmail(), u.getFullName(),
                    u.getRoles().stream().map(Enum::name).sorted().toList(), u.getStatus().name(), u.getCreatedAt());
        }
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserView user) {
    }

    public record AddressRequest(
            @NotBlank @Size(max = 40) String label,
            @NotBlank @Size(max = 120) String recipient,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @Size(max = 100) String state,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be an ISO-3166 alpha-2 code") String country,
            @Size(max = 30) @Pattern(regexp = "^[0-9+()\\-\\s]*$", message = "invalid phone") String phone,
            boolean makeDefault) {
    }

    public record AddressView(UUID id, String label, String recipient, String line1, String line2, String city,
                              String state, String postalCode, String country, String phone, boolean isDefault) {
        public static AddressView of(Address a) {
            return new AddressView(a.getId(), a.getLabel(), a.getRecipient(), a.getLine1(), a.getLine2(),
                    a.getCity(), a.getState(), a.getPostalCode(), a.getCountry(), a.getPhone(), a.isDefault());
        }
    }

    public record PageView<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
        public static <E, T> PageView<T> of(org.springframework.data.domain.Page<E> page,
                                            java.util.function.Function<E, T> mapper) {
            return new PageView<>(page.getContent().stream().map(mapper).toList(), page.getNumber(),
                    page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }
}
