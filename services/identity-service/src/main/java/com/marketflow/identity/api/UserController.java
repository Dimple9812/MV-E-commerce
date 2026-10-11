package com.marketflow.identity.api;

import com.marketflow.identity.api.Dtos.AddressRequest;
import com.marketflow.identity.api.Dtos.AddressView;
import com.marketflow.identity.api.Dtos.ChangePasswordRequest;
import com.marketflow.identity.api.Dtos.UpdateProfileRequest;
import com.marketflow.identity.api.Dtos.UserView;
import com.marketflow.identity.application.AuthService;
import com.marketflow.identity.application.UserService;
import com.marketflow.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** "Me" endpoints: the acting user is always taken from the verified token, never from the URL. */
@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Profile")
public class UserController {

    private final UserService users;
    private final AuthService auth;

    public UserController(UserService users, AuthService auth) {
        this.users = users;
        this.auth = auth;
    }

    @GetMapping
    public UserView me() {
        return UserView.of(users.get(AuthenticatedUser.current().userId()));
    }

    @PatchMapping
    public UserView update(@Valid @RequestBody UpdateProfileRequest req) {
        return UserView.of(users.rename(AuthenticatedUser.current().userId(), req.fullName()));
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        auth.changePassword(AuthenticatedUser.current().userId(), req.currentPassword(), req.newPassword());
    }

    @GetMapping("/addresses")
    public List<AddressView> addresses() {
        return users.addresses(AuthenticatedUser.current().userId()).stream().map(AddressView::of).toList();
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public AddressView addAddress(@Valid @RequestBody AddressRequest req) {
        return AddressView.of(users.addAddress(AuthenticatedUser.current().userId(), toData(req)));
    }

    @PutMapping("/addresses/{addressId}")
    public AddressView updateAddress(@PathVariable UUID addressId, @Valid @RequestBody AddressRequest req) {
        return AddressView.of(users.updateAddress(AuthenticatedUser.current().userId(), addressId, toData(req)));
    }

    @DeleteMapping("/addresses/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAddress(@PathVariable UUID addressId) {
        users.deleteAddress(AuthenticatedUser.current().userId(), addressId);
    }

    private static UserService.AddressData toData(AddressRequest r) {
        return new UserService.AddressData(r.label(), r.recipient(), r.line1(), r.line2(), r.city(), r.state(),
                r.postalCode(), r.country(), r.phone(), r.makeDefault());
    }
}
