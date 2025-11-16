import java.util.*;
import java.util.Objects; // CHANGED
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function; // CHANGED

interface RateLimitingStrategy {
    public boolean isAllowed();
    public void modifyRate(int rate);
    // CHANGE: Allow optional multi-permit acquire
    default boolean isAllowed(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be greater than zero");
        if (permits == 1) return isAllowed();
        // Default fallback to false, concrete classes should over-ride if they support multi-permits
        return false;
    }
};

// CHANGE: Immutable config holder, one volatile reference from client gives coherent snapshot across fields
final class RateLimiterConfig {
    final int ratePerSecond;
    final Integer maxBurstCapacity; // nullable field, null value would indicate unbounded burst
    /*
    maxBurstCapacity refers to the maximum number of tokens the bucket can hold.
    Caps how much “credit” can accumulate during idle time, which directly limits how large a burst can be served instantly.
    Without a cap (unbounded), long idle periods could allow very large bursts, potentially overwhelming downstream systems.
     */

    public RateLimiterConfig(int ratePerSecond, Integer maxBurstCapacity) {
        if (ratePerSecond <= 0) throw new IllegalArgumentException("ratePerSecond must be >0");
        if (maxBurstCapacity!=null && maxBurstCapacity <=0) throw new IllegalArgumentException("maxBurstCapacity must be >0");
        this.ratePerSecond = ratePerSecond;
        this.maxBurstCapacity = maxBurstCapacity;
    }

    public RateLimiterConfig withRate(int newRps) {
        return new RateLimiterConfig(newRps, this.maxBurstCapacity);
    }

    public RateLimiterConfig withCapacity(Integer newCapacity) {
        return new RateLimiterConfig(this.ratePerSecond, newCapacity);
    }
};

// CHANGE: Make class final
final class TokenBucketStrategy implements RateLimitingStrategy {
    // CHANGE: Single coherent config reference, instead of maintaining the properties separately
    private volatile RateLimiterConfig config;

    // CHANGE: Instead of maintaining a concurrent linked queue and an atomic integer counter, we can simply maintain a counter of available tokens.
    // If value of this variable 'availableTokens' > 0, it means a token is available, grant it, else not
    private long availableTokens = 0L;

    // CHANGE: The state (in our case, comprising of the availableTokens and lastRefillTime) are being guarded by this lock
    private final ReentrantLock lock = new ReentrantLock();

    private long lastRefillTimeNanos; // CHANGED: Add new field
    private final ScheduledExecutorService scheduler;

    public TokenBucketStrategy(int maxCapacity, int rate) {
        if (maxCapacity <= 0) throw new IllegalArgumentException("Maximum bucket capacity should be positive");
        if (rate <= 0) throw new IllegalArgumentException("Rate per second should be positive");
        this.config = new RateLimiterConfig(rate, maxCapacity);
        this.lastRefillTimeNanos = System.nanoTime();
        this.availableTokens = maxCapacity; // CHANGE: Start full

        // Daemon threads don’t keep the JVM alive. A refill thread is maintenance-only; the process should be able to exit even if you forget to shut it down.
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "token-bucket-refill");
            t.setDaemon(true);
            return t;
        };

        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);

        // CHANGE: Refill on a cadence, refill uses elapsed nanos to avoid drift
        scheduler.scheduleWithFixedDelay(this::refill, 0, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isAllowed() {
        return isAllowed(1);
    }

    @Override
    public boolean isAllowed(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be >0");
        // DEFENSIVE CODING: Always use try-finally while releasing locks
        lock.lock();
        try {
            // Refill automatically
            internalRefill();
            if (availableTokens >= permits) {
                availableTokens-=permits;
                return true;
            }
            return false;
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void modifyRate(int rate) {
        if (rate <= 0) throw new IllegalArgumentException("Rate per second should be positive");
        this.config = this.config.withRate(rate); // NOTE: We don't need to use lock to update the config since the config reference itself is getting updated (we are creating a new object)
    }


    public void modifyCapacity(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity should be positive");
        this.config = this.config.withCapacity(capacity);
        lock.lock();
        try {
            /*
                If capacity is reduced, you must clamp to enforce the new burst limit immediately.
                 Otherwise, the system could allow a burst larger than the new cap.
                 Note that we do acquire the lock here because availableTokens is being updated
             */
            if (capacity < availableTokens) availableTokens = capacity;
        }
        finally {
            lock.unlock();
        }
    }

    public void refill() {
        lock.lock();
        try {
            internalRefill();
        }
        finally {
            lock.unlock();
        }
    }

    // CHANGE: Elapsed time based refill
    public void internalRefill() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("internal refill must be called with lock held");
        }
        final long now = System.nanoTime();
        final long elapsed = now - lastRefillTimeNanos;
        if (elapsed <= 0) return ;
        final int rps = config.ratePerSecond;
        final long tokensToAdd = (elapsed * rps)/1_000_000_000L; // Unitary method: in 10^9 nanoseconds, tokens to add -> rps, in x nanoseconds -> tokens to add, (rps/10^9)*x
        if (tokensToAdd <=0) return ;
        final long cap = config.maxBurstCapacity!=null ? config.maxBurstCapacity: Long.MAX_VALUE; // Changed
        long newTokens = availableTokens + tokensToAdd;
        availableTokens = Math.min(newTokens, cap);

        // advance timestamp by the amount of time to produce x tokens
        // Unitary method: time to produce rps tokens -> 10^9 nanosec, time to produce x token -> 10^9/rps * x
        final long consumedNanos = (tokensToAdd * 1_000_000_000L)/rps;
        lastRefillTimeNanos += consumedNanos;
    }
};

class RateLimitingController {
    private final ConcurrentHashMap <String, RateLimitingStrategy> perKey = new ConcurrentHashMap<>();
    private final Function <String, RateLimitingStrategy> strategyFactory;

    public RateLimitingController(Function <String, RateLimitingStrategy> strategyFactory) {
        this.strategyFactory = Objects.requireNonNull(strategyFactory);
    }

    public boolean isAllowed(String key) {
        return isAllowed(key, 1);
    }

    public boolean isAllowed(String key, int permits) {
        RateLimitingStrategy rl = perKey.computeIfAbsent(key, strategyFactory);
        return rl.isAllowed(permits);
    }

    public void modifyRate(String key, int rate) {
        RateLimitingStrategy rl = perKey.computeIfAbsent(key, strategyFactory);
        rl.modifyRate(rate);
    }

};

public class Main {
    public static void main(String [] args) {
        RateLimitingController controller =
                new RateLimitingController(key -> new TokenBucketStrategy(10, 5));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        String[] users = {"alice", "bob", "carol"};
        int requestsPerUser = 50;

        var tasks = Arrays.stream(users)
                .map(user -> CompletableFuture.runAsync(() -> {
                    int allowed = 0, denied = 0;
                    for (int i = 0; i < requestsPerUser; i++) {
                        boolean ok = controller.isAllowed(user);
                        if (ok) allowed++; else denied++;
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    }
                    System.out.printf("user=%s allowed=%d denied=%d%n", user, allowed, denied);
                }, pool))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(tasks).join(); // wait for all
        pool.shutdownNow();
        System.out.println("Done.");
    }
};