package io.github.orhanyarkin.saiman.orchestrator.security;

import io.github.orhanyarkin.saiman.apisecurity.ConditionalOnSaimanAuth;
import io.github.orhanyarkin.saiman.apisecurity.SaimanAuthorities;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me}: who the caller is and what the token may do, so the dashboard can hide actions a READER
 * cannot take. Roles are expanded through the hierarchy: an OPERATOR token answers {@code ["OPERATOR","READER"]}.
 * Only the principal name ({@code <role>:<8 hex of the token digest>}) is read, never the credentials.
 */
@RestController
@ConditionalOnSaimanAuth // needs the RoleHierarchy and a principal; with authentication off there is nobody to describe
class MeController {

    private final RoleHierarchy hierarchy;

    MeController(RoleHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    @Operation(operationId = "getMe", summary = "The caller's principal name and effective roles")
    @ApiResponse(
            responseCode = "200",
            description = "The caller",
            content = @Content(schema = @Schema(implementation = MeResponse.class)))
    @GetMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
    MeResponse me(Authentication authentication) {
        List<String> roles = hierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(SaimanAuthorities.ROLE_PREFIX))
                .map(authority -> authority.substring(SaimanAuthorities.ROLE_PREFIX.length()))
                .distinct()
                .sorted()
                .toList();
        return new MeResponse(authentication.getName(), roles);
    }

    /** Body of {@code GET /api/v1/me}. */
    record MeResponse(String name, List<String> roles) {}
}
