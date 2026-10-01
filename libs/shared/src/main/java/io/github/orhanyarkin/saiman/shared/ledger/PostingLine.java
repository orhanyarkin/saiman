package io.github.orhanyarkin.saiman.shared.ledger;

import io.github.orhanyarkin.saiman.shared.money.Money;

/** One posting of a journal entry; the amount is always positive, direction is the side. */
public record PostingLine(String account, Side side, Money amount) {

    public PostingLine {
        if (account.isBlank() || account.length() > 128) {
            throw new IllegalArgumentException("account must be 1-128 characters");
        }
        if (amount.atomicUnits() <= 0) {
            throw new IllegalArgumentException("posting amounts are positive");
        }
    }
}
