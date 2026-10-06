package io.github.orhanyarkin.saiman.apisecurity;

import java.util.regex.Pattern;

/** Authority names shared by the services' request rules (ADR-0023). */
public final class SaimanAuthorities {

    /** Spring Security's role prefix. */
    public static final String ROLE_PREFIX = "ROLE_";

    /** Prefix of a service caller's authority: {@code SERVICE_ledger}, {@code SERVICE_evals}. */
    public static final String SERVICE_PREFIX = "SERVICE_";

    /**
     * The role hierarchy, in {@link org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl#fromHierarchy
     * RoleHierarchyImpl} syntax: an OPERATOR can do everything a READER can.
     */
    public static final String ROLE_HIERARCHY = "ROLE_OPERATOR > ROLE_READER";

    /**
     * A service caller name: lowercase letters and digits, starting with a letter. It is the key under {@code
     * saiman.auth.service.tokens}, so it also has to survive environment-variable binding
     * ({@code SAIMAN_AUTH_SERVICE_TOKENS_<CALLER>_SHA256}).
     */
    static final Pattern CALLER = Pattern.compile("^[a-z][a-z0-9]{0,31}$");

    private SaimanAuthorities() {}

    /**
     * The authority of one service caller, {@code SERVICE_<caller>}, e.g. {@code service("ledger")} is {@code
     * SERVICE_ledger}.
     *
     * @throws IllegalArgumentException if {@code caller} is not lowercase letters and digits
     */
    public static String service(String caller) {
        if (!CALLER.matcher(caller).matches()) {
            throw new IllegalArgumentException("Service caller names are lowercase letters and digits");
        }
        return SERVICE_PREFIX + caller;
    }
}
