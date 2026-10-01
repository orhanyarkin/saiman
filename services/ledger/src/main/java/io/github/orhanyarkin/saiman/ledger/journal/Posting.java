package io.github.orhanyarkin.saiman.ledger.journal;

import io.github.orhanyarkin.saiman.shared.ledger.PostingLine;
import io.github.orhanyarkin.saiman.shared.ledger.Side;
import io.github.orhanyarkin.saiman.shared.money.Money;

/** One leg of a journal entry: a positive amount on one side of one account. */
public record Posting(Account account, Side side, Money amount) {

    public Posting {
        if (amount.atomicUnits() <= 0) {
            throw new IllegalArgumentException("posting amounts are positive");
        }
        if (!account.asset().equals(amount.asset()) || account.decimals() != amount.decimals()) {
            throw new IllegalArgumentException("posting asset must match the account's asset");
        }
    }

    public static Posting debit(Account account, Money amount) {
        return new Posting(account, Side.DEBIT, amount);
    }

    public static Posting credit(Account account, Money amount) {
        return new Posting(account, Side.CREDIT, amount);
    }

    /** {@code +amount} for a debit, {@code -amount} for a credit. */
    public long signedAtomic() {
        return side == Side.DEBIT ? amount.atomicUnits() : -amount.atomicUnits();
    }

    /** The same posting on the other side, for reversals. */
    public Posting reversed() {
        return new Posting(account, side == Side.DEBIT ? Side.CREDIT : Side.DEBIT, amount);
    }

    public PostingLine toLine() {
        return new PostingLine(account.code(), side, amount);
    }
}
