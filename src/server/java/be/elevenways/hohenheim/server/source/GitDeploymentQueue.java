package be.elevenways.hohenheim.server.source;

import be.elevenways.protoblast.common.Blast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Per-site serial deployment queue with coalescing.
 * At most 1 running + 1 pending deploy at any time.
 * The pending slot is replaced by newer triggers (coalescing).
 */
public class GitDeploymentQueue {

    /** Global build semaphore: limits concurrent builds across all sites. */
    static final Semaphore BUILD_SEMAPHORE = new Semaphore(2);

    private final int siteId;
    private final ExecutorService executor;
    private final AtomicReference<String> pendingReason = new AtomicReference<>(null);
    private volatile boolean deploying = false;
    private volatile Thread deployThread = null;
    private volatile GitDeploymentResult lastResult = null;
    private volatile boolean cancelled = false;

    private final Consumer<String> deployAction;

    public GitDeploymentQueue(int siteId, Consumer<String> deployAction) {
        this.siteId = siteId;
        this.deployAction = deployAction;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "git-deploy-" + siteId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Enqueue a deploy. Only stores a reason -- the target slot is determined
     * at execution time, not enqueue time.
     */
    public synchronized void enqueue(String reason) {
        if (cancelled) return;

        // If nothing is running, submit directly
        if (!deploying) {
            deploying = true;
            executor.submit(() -> runDeploy(reason));
        } else {
            // Replace any existing pending deploy (coalesce)
            pendingReason.set(reason);
        }
    }

    private void runDeploy(String reason) {
        if (cancelled) return;

        deployThread = Thread.currentThread();
        long start = System.currentTimeMillis();

        try {
            // Acquire global build permit (interruptible -- responds to cancel())
            BUILD_SEMAPHORE.acquire();
            try {
                if (Thread.interrupted()) return;
                Blast.log("GIT: deploying site", siteId, "reason:", reason);
                deployAction.accept(reason);
                long duration = System.currentTimeMillis() - start;
                lastResult = new GitDeploymentResult(true, duration, reason, null);
                Blast.log("GIT: deploy succeeded for site", siteId, "in", duration + "ms");
            } finally {
                BUILD_SEMAPHORE.release();
            }
        } catch (InterruptedException e) {
            // Cancelled while waiting for build slot
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            lastResult = new GitDeploymentResult(false, duration, reason, e.getMessage());
            Blast.log("GIT: deploy failed for site", siteId, "-", e.getMessage());
        } finally {
            deployThread = null;

            // Check for pending deploy (synchronized with enqueue)
            synchronized (this) {
                deploying = false;
                String nextReason = pendingReason.getAndSet(null);
                if (nextReason != null && !cancelled) {
                    deploying = true;
                    executor.submit(() -> runDeploy(nextReason));
                }
            }
        }
    }

    /**
     * Cancel only the in-flight deploy (and drop the coalesced pending one) without
     * poisoning the queue: later triggers still deploy.
     *
     * @return true when a running deploy was interrupted
     */
    public synchronized boolean cancelCurrent() {
        pendingReason.set(null);
        Thread current = deployThread;
        if (current != null) {
            current.interrupt();
            return true;
        }
        return false;
    }

    /**
     * Cancel all pending and in-progress deploys.
     * Blocks until the current build thread exits (30s timeout).
     */
    public void cancel() {
        cancelled = true;
        pendingReason.set(null);

        Thread current = deployThread;
        if (current != null) {
            current.interrupt();
            try {
                current.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isDeploying() {
        return deploying;
    }

    public GitDeploymentResult getLastResult() {
        return lastResult;
    }

    public record GitDeploymentResult(boolean success, long durationMs, String reason, String error) {}
}
