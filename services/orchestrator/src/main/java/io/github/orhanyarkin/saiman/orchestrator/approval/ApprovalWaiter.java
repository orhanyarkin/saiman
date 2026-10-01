package io.github.orhanyarkin.saiman.orchestrator.approval;

import io.github.orhanyarkin.saiman.orchestrator.budget.SpendProperties;
import io.github.orhanyarkin.saiman.orchestrator.events.AfterCommit;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lets a run's (virtual) thread wait for a human decision. The future only wakes the thread; the
 * answer is always re-read from the {@code approval} table, which is the source of truth. A wait that
 * times out expires the approval in the database (unless a decision won the race).
 *
 * <p>Single-instance by design (ADR-0014): a decision made on another instance would still be seen
 * at the latest when the wait times out, because the DB row is re-read then.
 */
@Component
public class ApprovalWaiter {

    private final ApprovalService approvals;
    private final Duration defaultTimeout;
    private final ConcurrentHashMap<UUID, CompletableFuture<ApprovalStatus>> waiting = new ConcurrentHashMap<>();

    public ApprovalWaiter(ApprovalService approvals, SpendProperties spend) {
        this.approvals = approvals;
        this.defaultTimeout = spend.approvalTimeout();
    }

    /** Waits up to the configured approval timeout (bounded by the approval's own expiry). */
    public ApprovalStatus await(UUID approvalId) {
        return await(approvalId, defaultTimeout);
    }

    /**
     * Waits up to the configured approval timeout but never past {@code deadline} (the run's
     * wall-clock deadline); an approval still PENDING then expires as on a timeout.
     */
    public ApprovalStatus awaitUntil(UUID approvalId, Instant deadline) {
        Duration untilDeadline =
                Instant.MAX.equals(deadline) ? defaultTimeout : Duration.between(Instant.now(), deadline);
        Duration wait = untilDeadline.compareTo(defaultTimeout) < 0 ? untilDeadline : defaultTimeout;
        return await(approvalId, wait.isNegative() ? Duration.ZERO : wait);
    }

    /**
     * Waits for a decision on {@code approvalId}, at most {@code timeout} and never past the
     * approval's {@code expires_at}.
     *
     * @return APPROVED, REJECTED or EXPIRED, as stored in the database; EXPIRED without any write if
     *     the waiting thread is interrupted (the interrupt flag is restored)
     * @throws ApprovalNotFoundException if the approval does not exist
     */
    public ApprovalStatus await(UUID approvalId, Duration timeout) {
        // Register before reading the row, so a decision committed in between is not missed.
        CompletableFuture<ApprovalStatus> future = waiting.computeIfAbsent(approvalId, id -> new CompletableFuture<>());
        try {
            ApprovalView current = approvals.find(approvalId).orElseThrow(ApprovalNotFoundException::new);
            if (current.status() != ApprovalStatus.PENDING) {
                return current.status();
            }
            Duration untilExpiry = Duration.between(Instant.now(), current.expiresAt());
            Duration wait = untilExpiry.compareTo(timeout) < 0 ? untilExpiry : timeout;
            if (!wait.isNegative() && !wait.isZero()) {
                future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException e) {
            // Fall through: expire below.
        } catch (InterruptedException e) {
            // The run is being torn down: nobody decided, so nothing is written here. The approval
            // stays PENDING and the run's finish expires it (and releases an intent approved in the
            // meantime), in the same transaction that ends the run.
            Thread.currentThread().interrupt();
            return ApprovalStatus.EXPIRED;
        } catch (ExecutionException e) {
            // Never completed exceptionally; the DB decides below.
        } finally {
            waiting.remove(approvalId, future);
        }
        ApprovalView decided = approvals.find(approvalId).orElseThrow(ApprovalNotFoundException::new);
        return decided.status() == ApprovalStatus.PENDING ? approvals.expire(approvalId) : decided.status();
    }

    /** Wakes the waiter once the deciding transaction has committed. */
    @EventListener
    void onDecided(ApprovalDecidedEvent event) {
        AfterCommit.run(() -> {
            CompletableFuture<ApprovalStatus> future = waiting.get(event.approvalId());
            if (future != null) {
                future.complete(event.status());
            }
        });
    }
}
