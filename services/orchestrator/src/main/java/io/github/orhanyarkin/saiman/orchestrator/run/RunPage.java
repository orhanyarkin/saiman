package io.github.orhanyarkin.saiman.orchestrator.run;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A page of {@code GET /api/v1/runs}, newest first.
 *
 * @param next opaque cursor for the {@code before} parameter of the next page; null on the last page
 */
public record RunPage(List<RunListItem> items, @Nullable String next) {}
