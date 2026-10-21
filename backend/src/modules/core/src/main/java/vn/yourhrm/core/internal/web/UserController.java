package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.UserService;
import vn.yourhrm.core.internal.application.UserService.Page;
import vn.yourhrm.core.internal.application.UserService.Profile;
import vn.yourhrm.core.internal.application.UserService.UserView;
import vn.yourhrm.core.internal.domain.UserStatus;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** Tenant user accounts (US-CORE-030). */
@RestController
@RequestMapping("/api/v1/core/users")
class UserController {

    private static final String LOCALE = "^[a-z]{2}(-[A-Z]{2})?$";

    private final UserService users;

    UserController(UserService users) {
        this.users = users;
    }

    record CreateUserRequest(
            @NotBlank @Size(max = 150) String login,
            @Email @Size(max = 254) String email,
            @Size(max = 32) String mobile,
            @Pattern(regexp = LOCALE) String locale,
            UUID identityId,
            UUID employmentId,
            UUID personId,
            Instant expiresAt) {
    }

    record UpdateUserRequest(
            @Email @Size(max = 254) String email,
            @Size(max = 32) String mobile,
            @Pattern(regexp = LOCALE) String locale,
            UUID identityId,
            UUID employmentId,
            UUID personId,
            Instant expiresAt,
            @NotNull Integer version) {
    }

    @GetMapping
    @RequiresPermission(value = CorePermissions.USER_VIEW, tenantWide = true)
    public Page<UserView> search(@RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) UserStatus status,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return users.search(q, status, Math.max(page, 0), Math.min(Math.max(size, 1), 200));
    }

    @GetMapping("/{userId}")
    @RequiresPermission(value = CorePermissions.USER_VIEW, tenantWide = true)
    public UserView get(@PathVariable UUID userId) {
        return users.get(userId);
    }

    @PostMapping
    @RequiresPermission(value = CorePermissions.USER_CREATE, tenantWide = true)
    public ResponseEntity<UserView> create(@Valid @RequestBody CreateUserRequest req) {
        UserView created = users.create(req.login().trim(), new Profile(req.email(), req.mobile(), req.locale(),
                req.identityId(), req.employmentId(), req.personId(), req.expiresAt()));
        return ResponseEntity.created(URI.create("/api/v1/core/users/" + created.id())).body(created);
    }

    @PutMapping("/{userId}")
    @RequiresPermission(value = CorePermissions.USER_UPDATE, tenantWide = true)
    public UserView update(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest req) {
        return users.update(userId, new Profile(req.email(), req.mobile(), req.locale(), req.identityId(),
                req.employmentId(), req.personId(), req.expiresAt()), req.version());
    }

    @PostMapping("/{userId}/activate")
    @RequiresPermission(value = CorePermissions.USER_UPDATE, tenantWide = true)
    public UserView activate(@PathVariable UUID userId) {
        return users.activate(userId);
    }

    @PostMapping("/{userId}/deactivate")
    @RequiresPermission(value = CorePermissions.USER_UPDATE, tenantWide = true)
    public UserView deactivate(@PathVariable UUID userId) {
        return users.deactivate(userId);
    }
}
