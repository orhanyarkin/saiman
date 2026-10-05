package io.github.orhanyarkin.saiman.ledger;

import io.github.orhanyarkin.saiman.ledger.reconciliation.SellerCreditNoteClient;
import io.github.orhanyarkin.saiman.ledger.reconciliation.SellerCreditNoteClient.SellerUnauthorizedException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Primary;

/**
 * An in-memory seller-api for hermetic integration tests, next to {@link FakeChain}: {@code @Primary}, so
 * reconciliation never reaches the real HTTP client. Unknown keys have no credit note, like a seller that never
 * issued one; {@link #down(String)} makes one key unanswerable.
 */
@Primary
public class FakeSellerCreditNotes implements SellerCreditNoteClient {

    private final Map<String, SellerCreditNote> notes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Set<String> down = ConcurrentHashMap.newKeySet();
    private final Set<String> unauthorized = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<SellerCreditNote> find(String paymentKey) {
        calls.computeIfAbsent(paymentKey, k -> new AtomicInteger()).incrementAndGet();
        if (unauthorized.contains(paymentKey)) {
            throw new SellerUnauthorizedException("seller answered HTTP 401");
        }
        if (down.contains(paymentKey)) {
            throw new SellerUnavailableException("test: seller down");
        }
        return Optional.ofNullable(notes.get(paymentKey));
    }

    /** The seller recorded this credit note. */
    public void issue(String paymentKey, String txHash, long amountAtomic) {
        notes.put(paymentKey, new SellerCreditNote(txHash.toLowerCase(Locale.ROOT), amountAtomic));
    }

    /** Lookups of this key fail as if the seller were unreachable. */
    public void down(String paymentKey) {
        down.add(paymentKey);
    }

    /** Lookups of this key fail as if the seller refused the ledger's service token. */
    public void unauthorized(String paymentKey) {
        unauthorized.add(paymentKey);
    }

    /** Lookups of this key work again. */
    public void up(String paymentKey) {
        down.remove(paymentKey);
        unauthorized.remove(paymentKey);
    }

    /** How often this key was looked up. */
    public int calls(String paymentKey) {
        AtomicInteger count = calls.get(paymentKey);
        return count == null ? 0 : count.get();
    }
}
