package com.marketflow.identity.api;

import com.marketflow.identity.api.Dtos.PageView;
import com.marketflow.identity.api.Dtos.UserView;
import com.marketflow.identity.application.UserService;
import com.marketflow.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin - users")
public class AdminUserController {

    private final UserService users;

    public AdminUserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public PageView<UserView> list(@RequestParam(required = false) String q,
                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageView.of(users.search(q, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))),
                UserView::of);
    }

    @GetMapping("/{userId}")
    public UserView get(@PathVariable UUID userId) {
        return UserView.of(users.get(userId));
    }

    @PostMapping("/{userId}/deactivate")
    public UserView deactivate(@PathVariable UUID userId) {
        return UserView.of(users.setActive(AuthenticatedUser.current().userId(), userId, false));
    }

    @PostMapping("/{userId}/activate")
    public UserView activate(@PathVariable UUID userId) {
        return UserView.of(users.setActive(AuthenticatedUser.current().userId(), userId, true));
    }
}
