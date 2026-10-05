/**
 * Cross-cutting support for the unauthenticated dashboard reads: a bounded read-only transaction
 * ({@link io.github.orhanyarkin.saiman.orchestrator.dashboard.BoundedReads}) and API error handling
 * that never echoes request input.
 */
@NullMarked
package io.github.orhanyarkin.saiman.orchestrator.dashboard;

import org.jspecify.annotations.NullMarked;
