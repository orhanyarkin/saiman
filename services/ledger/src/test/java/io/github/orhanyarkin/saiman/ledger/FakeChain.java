package io.github.orhanyarkin.saiman.ledger;

import io.github.orhanyarkin.saiman.evmrpc.BaseSepoliaUsdc;
import io.github.orhanyarkin.saiman.evmrpc.BlockTag;
import io.github.orhanyarkin.saiman.evmrpc.ChainBlock;
import io.github.orhanyarkin.saiman.evmrpc.ChainUnavailableException;
import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * An in-memory Base Sepolia for hermetic integration tests (ADR-0018 rule 5): the application's
 * {@link BaseSepoliaUsdc} bean in every test context, so the auto-configured JSON-RPC client is never built.
 * Unknown tx hashes have no receipt and unknown authorizations are unused, like a real chain.
 */
public class FakeChain implements BaseSepoliaUsdc {

    /** Safe block: an hour after the test payments' validBefore. */
    public static final ChainBlock SAFE = new ChainBlock(5_000_000, 1_790_003_660L);

    private final Map<String, UsdcReceipt> receipts = new ConcurrentHashMap<>();
    private final Map<String, String> usedBy = new ConcurrentHashMap<>();
    private final Set<String> used = ConcurrentHashMap.newKeySet();
    private final Set<String> unavailableTx = ConcurrentHashMap.newKeySet();
    private final Set<String> brokenTx = ConcurrentHashMap.newKeySet();
    private volatile @Nullable CountDownLatch safeBlockGate;
    private volatile @Nullable CountDownLatch safeBlockEntered;

    @Override
    public ChainBlock block(BlockTag tag) {
        CountDownLatch gate = safeBlockGate;
        CountDownLatch entered = safeBlockEntered;
        if (entered != null) {
            entered.countDown();
        }
        if (gate != null) {
            try {
                if (!gate.await(30, TimeUnit.SECONDS)) {
                    throw new ChainUnavailableException("test gate timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ChainUnavailableException("interrupted");
            }
        }
        return SAFE;
    }

    @Override
    public boolean authorizationState(String authorizer, String nonce, long blockNumber) {
        return used.contains(key(authorizer, nonce));
    }

    @Override
    public Optional<UsdcReceipt> receipt(String txHash) {
        String tx = txHash.toLowerCase(Locale.ROOT);
        if (unavailableTx.contains(tx)) {
            throw new ChainUnavailableException("receipt lookup failed");
        }
        if (brokenTx.contains(tx)) {
            throw new IllegalStateException("unexpected failure");
        }
        return Optional.ofNullable(receipts.get(tx));
    }

    @Override
    public Optional<String> findAuthorizationTx(String authorizer, String nonce, long fromBlock, long toBlock) {
        return Optional.ofNullable(usedBy.get(key(authorizer, nonce)));
    }

    /** Mines a receipt; a successful one that carries AuthorizationUsed marks those authorizations used. */
    public void mine(UsdcReceipt receipt) {
        String tx = receipt.txHash().toLowerCase(Locale.ROOT);
        receipts.put(tx, receipt);
        if (receipt.succeeded()) {
            for (String auth : receipt.authorizationsUsed()) {
                String k = auth.toLowerCase(Locale.ROOT);
                used.add(k);
                usedBy.putIfAbsent(k, tx);
            }
        }
    }

    /** The RPC fails for this tx hash only. */
    public void failReceipt(String txHash) {
        unavailableTx.add(txHash.toLowerCase(Locale.ROOT));
    }

    /** The lookup for this tx hash fails with an unexpected (non-RPC) exception. */
    public void breakReceipt(String txHash) {
        brokenTx.add(txHash.toLowerCase(Locale.ROOT));
    }

    public void healReceipt(String txHash) {
        unavailableTx.remove(txHash.toLowerCase(Locale.ROOT));
        brokenTx.remove(txHash.toLowerCase(Locale.ROOT));
    }

    /** Makes {@code block(SAFE)} wait until {@link #openGate()}; returns a latch counted down when a run is inside. */
    public CountDownLatch closeGate() {
        CountDownLatch entered = new CountDownLatch(1);
        safeBlockEntered = entered;
        safeBlockGate = new CountDownLatch(1);
        return entered;
    }

    public void openGate() {
        CountDownLatch gate = safeBlockGate;
        safeBlockGate = null;
        safeBlockEntered = null;
        if (gate != null) {
            gate.countDown();
        }
    }

    private static String key(String authorizer, String nonce) {
        return (authorizer + ":" + nonce).toLowerCase(Locale.ROOT);
    }
}
