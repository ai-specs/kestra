package io.kestra.oidc.controllers;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.nimbusds.jwt.JWTClaimsSet;

import io.kestra.oidc.services.OidcTokenService;
import io.kestra.oidc.services.OidcUserService;
import io.kestra.oidc.services.OidcUserService.OidcUser;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;

/**
 * User-directory administration API for the Kestra UI (用户控制 / 权限控制 pages).
 *
 * <p>
 * Sits under {@code /api/v1/oidc/users} — <b>not</b> under {@code /api/v1/dsh/} — so it is
 * <i>not</i> subject to the Bearer-only {@link OidcBearerAuthFilter} guard: the UI calls it with
 * the OIDC session cookie, while dsh service clients (and the e2e suite) may call it with a
 * provider-issued Bearer access token. Both paths are validated here and both require the
 * {@code admin} role.
 *
 * <p>
 * Only the profile/authorisation surface is exposed; credential hashes are never read back.
 * No multi-tenancy — this is a single-directory deployment.
 */
@Controller("/api/v1/oidc/users")
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
@ExecuteOn(TaskExecutors.IO)
public class OidcUserAdminController {

    private static final String ADMIN_ROLE = "admin";

    @Inject
    private OidcUserService userService;

    @Inject
    private OidcTokenService tokenService;

    // ------------------------------------------------------------------ list / create

    /** Lists users, newest first. {@code search} filters on username/name/email;
     *  {@code type} filters on identity type ({@code human} / {@code machine}, empty = all). */
    @Get
    public HttpResponse<?> list(
        HttpRequest<?> request,
        @QueryValue(defaultValue = "") String search,
        @QueryValue(defaultValue = "") String type,
        @QueryValue(defaultValue = "0") int offset,
        @QueryValue(defaultValue = "100") int size
    ) {
        requireAdmin(request);
        return HttpResponse.ok(userService.listUsers(search, type, offset, size));
    }

    /** Creates a user (with an optional initial bcrypt password). */
    @Post(consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<?> create(HttpRequest<?> request, @Body OidcUserService.CreateUserRequest body) {
        requireAdmin(request);
        if (body == null) {
            return HttpResponse.badRequest(Map.of("error", "request body is required"));
        }
        try {
            OidcUserService.UserRow row = userService.createUser(body);
            return HttpResponse.status(HttpStatus.CREATED).body(row);
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            if (isUniqueViolation(e)) {
                return HttpResponse.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "user '" + body.username() + "' already exists"));
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ detail / update / delete

    /** Full profile of one user, including auth methods (no credential hashes). */
    @Get("/{username}")
    public HttpResponse<?> get(HttpRequest<?> request, String username) {
        requireAdmin(request);
        Optional<OidcUserService.UserDetail> detail = userService.getUser(decode(username));
        return detail.<HttpResponse<?>>map(HttpResponse::ok)
            .orElseGet(() -> HttpResponse.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "user '" + decode(username) + "' does not exist")));
    }

    /** Updates the profile fields of an existing user. */
    @Put(value = "/{username}", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<?> update(
        HttpRequest<?> request,
        String username,
        @Body OidcUserService.UpdateUserRequest body
    ) {
        requireAdmin(request);
        if (body == null) {
            return HttpResponse.badRequest(Map.of("error", "request body is required"));
        }
        try {
            return HttpResponse.ok(userService.updateUser(decode(username), body));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        }
    }

    /** Deletes a user (auth methods cascade). */
    @Delete("/{username}")
    public HttpResponse<?> delete(HttpRequest<?> request, String username) {
        requireAdmin(request);
        try {
            boolean deleted = userService.deleteUser(decode(username));
            return deleted
                ? HttpResponse.noContent()
                : HttpResponse.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "user '" + decode(username) + "' does not exist"));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ authorisation (权限控制)

    /** Lists project-scoped roles with descriptions and member counts (default dsh project). */
    @Get("/roles")
    public HttpResponse<?> listRoles(HttpRequest<?> request) {
        requireAdmin(request);
        return HttpResponse.ok(userService.listRoles("dsh"));
    }

    /** Replaces the roles of a user. */
    @Put(value = "/{username}/roles", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<?> setRoles(HttpRequest<?> request, String username, @Body Map<String, List<String>> body) {
        requireAdmin(request);
        if (body == null || body.get("roles") == null) {
            return HttpResponse.badRequest(Map.of("error", "{\"roles\": [...]} is required"));
        }
        try {
            return HttpResponse.ok(userService.setRoles(decode(username), body.get("roles")));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        }
    }

    /** Sets/replaces the PASSWORD auth method of a user. */
    @Post(value = "/{username}/password", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<?> resetPassword(
        HttpRequest<?> request,
        String username,
        @Body Map<String, String> body
    ) {
        requireAdmin(request);
        if (body == null || body.get("password") == null || body.get("password").isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "{\"password\": ...} is required"));
        }
        try {
            boolean ok = userService.resetPassword(decode(username), body.get("password"));
            return ok
                ? HttpResponse.ok(Map.of("status", "password updated"))
                : HttpResponse.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "user '" + decode(username) + "' does not exist"));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        }
    }

    /** Rotates the client secret of a machine identity (service account). */
    @Post(value = "/{username}/secret", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<?> rotateSecret(
        HttpRequest<?> request,
        String username,
        @Body Map<String, String> body
    ) {
        requireAdmin(request);
        String secret = body == null ? null : body.get("secret");
        try {
            String rotated = userService.rotateSecret(decode(username), secret);
            return HttpResponse.ok(Map.of("status", "client secret rotated", "secret", rotated));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ auth

    /**
     * Resolves an admin principal from either the OIDC session cookie (Kestra UI) or a Bearer
     * access token (dsh service clients / e2e). Rejects non-admin principals and unauthenticated
     * callers.
     */
    private void requireAdmin(HttpRequest<?> request) {
        // 1) Session cookie path — the Kestra UI is authenticated through /oidc/login.
        Optional<OidcUser> fromSession = userService.authenticatedUser(request);
        if (fromSession.isPresent()) {
            if (hasRole(fromSession.get().roles(), ADMIN_ROLE)) {
                return;
            }
            throw new HttpStatusException(HttpStatus.FORBIDDEN, Map.of(
                "error", "forbidden",
                "error_description", "admin role is required"));
        }

        // 2) Bearer path — dsh service clients with a provider-issued access token.
        String authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            try {
                JWTClaimsSet claims = tokenService.validateAccessToken(authorization.substring("Bearer ".length()).trim());
                List<String> roles = claims.getStringListClaim("roles") == null
                    ? List.of()
                    : claims.getStringListClaim("roles");
                if (hasRole(roles, ADMIN_ROLE)) {
                    return;
                }
                throw new HttpStatusException(HttpStatus.FORBIDDEN, Map.of(
                    "error", "forbidden",
                    "error_description", "admin role is required"));
            } catch (HttpStatusException e) {
                throw e;
            } catch (Exception e) {
                throw new HttpStatusException(HttpStatus.UNAUTHORIZED, Map.of(
                    "error", "invalid_token",
                    "error_description", e.getMessage()));
            }
        }

        throw new HttpStatusException(HttpStatus.UNAUTHORIZED, Map.of(
            "error", "authentication required",
            "error_description", "OIDC session cookie or Bearer access token required"));
    }

    private static boolean hasRole(List<String> roles, String role) {
        return roles != null && roles.stream().anyMatch(role::equalsIgnoreCase);
    }

    private static String decode(String username) {
        return username == null ? null : URLDecoder.decode(username, StandardCharsets.UTF_8);
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && (message.contains("duplicate key") || message.contains("unique constraint"))) {
                return true;
            }
        }
        return false;
    }
}
