/**
 * Static role tokens verified behind Spring Security's resource-server seam (ADR-0023). The verifier is the only part
 * that knows tokens are static; it can be swapped for a JWT resource server without touching controllers or rules.
 */
@NullMarked
package io.github.orhanyarkin.saiman.apisecurity;

import org.jspecify.annotations.NullMarked;
