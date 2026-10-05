package io.github.orhanyarkin.saiman.apisecurity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The three API roles (ADR-0023). Each maps to the Spring Security authority {@code ROLE_<name>}, so request rules use
 * {@code hasRole("READER")} or {@code hasAuthority(SaimanRole.READER.authority())}.
 *
 * <ul>
 *   <li>{@link #READER}: reads (every GET of the dashboard APIs).
 *   <li>{@link #OPERATOR}: includes READER through the {@code RoleHierarchy} bean ({@code ROLE_OPERATOR >
 *       ROLE_READER}); starts runs, decides approvals, runs reconciliation.
 *   <li>{@link #SERVICE}: a service-to-service caller. Its token also carries {@code SERVICE_<caller>} (see
 *       {@link SaimanAuthorities#service(String)}), which is what internal routes check.
 * </ul>
 */
public enum SaimanRole {
    READER,
    OPERATOR,
    SERVICE;

    /** The authority name, {@code ROLE_<name>}. */
    public String authority() {
        return SaimanAuthorities.ROLE_PREFIX + name();
    }

    /** The authority as a {@link GrantedAuthority}. */
    public GrantedAuthority grantedAuthority() {
        return new SimpleGrantedAuthority(authority());
    }
}
