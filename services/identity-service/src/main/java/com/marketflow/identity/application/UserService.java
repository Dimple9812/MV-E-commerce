package com.marketflow.identity.application;

import com.marketflow.identity.domain.Address;
import com.marketflow.identity.domain.User;
import com.marketflow.identity.persistence.AddressRepository;
import com.marketflow.identity.persistence.UserRepository;
import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.outbox.OutboxWriter;
import com.marketflow.platform.security.Role;
import com.marketflow.platform.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private static final int MAX_ADDRESSES = 10;

    private final UserRepository users;
    private final AddressRepository addresses;
    private final RefreshTokenService refreshTokens;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;

    public UserService(UserRepository users, AddressRepository addresses, RefreshTokenService refreshTokens,
                       OutboxWriter outbox, AuditRecorder audit) {
        this.users = users;
        this.addresses = addresses;
        this.refreshTokens = refreshTokens;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional
    public User rename(UUID id, String fullName) {
        User user = get(id);
        user.rename(fullName);
        return user;
    }

    @Transactional(readOnly = true)
    public Page<User> search(String q, Pageable pageable) {
        return users.search(q == null || q.isBlank() ? null : q.trim(), pageable);
    }

    @Transactional
    public User setActive(UUID adminId, UUID userId, boolean active) {
        if (adminId.equals(userId) && !active) {
            throw ApiException.unprocessable("self_deactivation", "Administrators cannot deactivate themselves");
        }
        User user = get(userId);
        if (active) {
            user.activate();
        } else {
            user.deactivate();
            refreshTokens.revokeAll(userId); // access tokens expire within their short TTL
        }
        outbox.append(IdentityEvents.USER_TOPIC, "UserStatusChanged", 1, IdentityEvents.AGGREGATE,
                userId.toString(), new IdentityEvents.UserStatusChanged(userId, user.getStatus().name()));
        audit.record(adminId, "ADMIN", active ? "user.activated" : "user.deactivated", "User", userId.toString(),
                Map.of());
        return user;
    }

    /** Called from the vendor event consumer; never reachable from a user-facing endpoint. */
    @Transactional
    public void applyVendorRole(UUID userId, boolean grant, String reason) {
        User user = users.findById(userId).orElse(null);
        if (user == null) {
            return; // unknown user: nothing to do (vendor-service validated ownership against identity events)
        }
        boolean changed = grant ? user.grant(Role.VENDOR) : user.revoke(Role.VENDOR);
        if (changed) {
            outbox.append(IdentityEvents.USER_TOPIC, "UserRolesChanged", 1, IdentityEvents.AGGREGATE,
                    userId.toString(), new IdentityEvents.UserRolesChanged(userId,
                            user.getRoles().stream().map(Enum::name).sorted().toList(), reason));
            audit.record(null, "SYSTEM", grant ? "role.vendor_granted" : "role.vendor_revoked", "User",
                    userId.toString(), Map.of("reason", reason));
        }
    }

    // ---- addresses ----

    @Transactional(readOnly = true)
    public List<Address> addresses(UUID userId) {
        return addresses.findByUserIdOrderByCreatedAtAsc(userId);
    }

    public record AddressData(String label, String recipient, String line1, String line2, String city,
                              String state, String postalCode, String country, String phone, boolean makeDefault) {
    }

    @Transactional
    public Address addAddress(UUID userId, AddressData data) {
        if (addresses.countByUserId(userId) >= MAX_ADDRESSES) {
            throw ApiException.unprocessable("address_limit", "At most " + MAX_ADDRESSES + " addresses allowed");
        }
        Address a = new Address(userId);
        apply(a, data);
        boolean first = addresses.countByUserId(userId) == 0;
        if (data.makeDefault() || first) {
            addresses.clearDefault(userId);
            a.setDefault(true);
        }
        return addresses.save(a);
    }

    @Transactional
    public Address updateAddress(UUID userId, UUID addressId, AddressData data) {
        Address a = addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> ApiException.notFound("Address"));
        apply(a, data);
        if (data.makeDefault() && !a.isDefault()) {
            addresses.clearDefault(userId);
            a.setDefault(true);
        }
        return a;
    }

    @Transactional
    public void deleteAddress(UUID userId, UUID addressId) {
        Address a = addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> ApiException.notFound("Address"));
        addresses.delete(a);
    }

    private static void apply(Address a, AddressData d) {
        a.update(d.label(), d.recipient(), d.line1(), d.line2(), d.city(), d.state(), d.postalCode(), d.country(),
                d.phone());
    }
}
