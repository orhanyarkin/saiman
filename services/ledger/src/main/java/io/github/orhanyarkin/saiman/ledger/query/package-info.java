/**
 * The dashboard's read model (M5, ADR-0022): payment pages and drill-downs, seller revenue. Read-only SQL over the
 * ledger tables; the response records here are the public JSON contract ({@code docs/api/ledger.openapi.json}). They
 * address payments by id only: no payment key and no nonce ever leaves this package.
 */
@NullMarked
package io.github.orhanyarkin.saiman.ledger.query;

import org.jspecify.annotations.NullMarked;
