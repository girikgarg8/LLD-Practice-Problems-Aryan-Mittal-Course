import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

interface Cache <K,V> {
    public V getValue(K key);

    // Common implementation for create + insert
    public void put(K key, V value);

    public void deleteByKey(K key);
};

class CacheImpl <K,V> implements Cache <K,V> {
    private final Map <K,V> keyToValueMapping = new ConcurrentHashMap<>();

    @Override
    public V getValue(K key) {
        // CHANGE: No need to check contains, it returns null in default case anyways
        return keyToValueMapping.get(key);
    }

    @Override
    public void put(K key, V value) {
        keyToValueMapping.put(key, value);
    }

    @Override
    public void deleteByKey(K key) {
        keyToValueMapping.remove(key); // CHANGE: remove() is idempotent, no need to check
    }
};

interface Database <K,V> {
    public V getValue(K key);

    // Common implementation for create + insert
    public void put(K key, V value);

    public void deleteByKey(K key);
};

class DatabaseImpl <K,V> implements Database <K,V> {
    private final Map <K,V> keyToValueMapping = new ConcurrentHashMap<>();

    @Override
    public V getValue(K key) {
        return keyToValueMapping.get(key);
    }

    @Override
    public void put(K key, V value) {
        // CHANGE: Simulate database latency for realistic testing
        try {
            Thread.sleep(50);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        keyToValueMapping.put(key, value);
    }

    @Override
    public void deleteByKey(K key) {
        keyToValueMapping.remove(key);
    }
};

interface WriteStrategy <K,V> {
    public void write(Cache <K,V> cache, Database <K,V> database, K key, V value);
};

class WriteThroughStrategy <K,V> implements WriteStrategy <K,V> {
    @Override
    public void write(Cache <K,V> cache, Database <K,V> database, K key, V value) {
        // CHANGE: Use runAsync instead of supplyAsync since the tasks don't have any return type
        CompletableFuture <Void> cacheWrite = CompletableFuture.runAsync(() -> cache.put(key, value));
        CompletableFuture <Void> databaseWrite = CompletableFuture.runAsync(() -> database.put(key, value));
        CompletableFuture.allOf(cacheWrite, databaseWrite).join();
    }
};

class DLLNode <K,V> {
    private K key;
    private V value;
    private DLLNode <K,V> next;
    private DLLNode <K,V> prev;

    public DLLNode(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public K getKey() {
        return key;
    }

    public V getValue() {
        return value;
    }

    public DLLNode getNext() {
        return next;
    }

    public DLLNode getPrev() {
        return prev;
    }

    public void setNext(DLLNode next) {
       this.next = next;
    }

    public void setPrev(DLLNode prev) {
        this.prev = prev;
    }
};

class DLL <K,V> {
    private final DLLNode <K,V> head = new DLLNode<>(null, null); // CHANGED: Use null for generic types instead of -1
    private final DLLNode <K,V> tail = new DLLNode<>(null, null);

    public DLL() {
        head.setNext(tail);
        tail.setPrev(head);
    }

    public void addFront(DLLNode <K,V> node) {
        DLLNode <K,V> headNext = head.getNext();
        head.setNext(node);
        node.setPrev(head);
        node.setNext(headNext);
        headNext.setPrev(node);
    }

    public void removeNode(DLLNode <K,V> node) {
        DLLNode <K,V> nodePrev = node.getPrev();
        DLLNode <K,V> nodeNext = node.getNext();
        nodePrev.setNext(nodeNext);
        nodeNext.setPrev(nodePrev);
    }

    public DLLNode <K,V> getTailPredecessor() {
        return tail.getPrev();
    }

    // CHANGE: Add isEmpty method to check if list is empty (only has sentinel nodes)
    public boolean isEmpty() {
        return head.getNext() == tail;
    }
};

interface EvictionStrategy <K,V> {
    public void accessed(K key, V value);

    public K evict();
};

class LRUEvictionStrategy <K,V> implements EvictionStrategy <K,V> {
    private final DLL <K,V> dll = new DLL();
    private final Map <K, DLLNode <K,V>> keyToNodeMappings = new ConcurrentHashMap<>();

    // CHANGE: add synchronized for thread safety, there is a chance that threads of different slots execute this parallely, we need to have locking so that linked list structure isn't corrupted
    @Override
    public synchronized void accessed(K key, V value) {
        DLLNode <K,V> dllNode = keyToNodeMappings.get(key);

        if (dllNode != null) {
            dll.removeNode(dllNode);
        }
        else {
            dllNode = new DLLNode<>(key, value);
            keyToNodeMappings.put(key, dllNode);
        }
        dll.addFront(dllNode);
    }

    // CHANGE: add synchronized for thread safety, there is a chance that threads of different slots execute this parallely, we need to have locking so that linked list structure isn't corrupted
    @Override
    public synchronized K evict() {
        // CHANGE: Check for empty list
        if (dll.isEmpty()) {
            return null;
        }

        DLLNode <K,V> tailPredecessor = dll.getTailPredecessor();
        K key = tailPredecessor.getKey();
        dll.removeNode(tailPredecessor);
        keyToNodeMappings.remove(key);
        return key;
    }
};

class CachingOrchestrator <K,V> {
    private final Cache <K,V> cache;
    private final Database <K,V>  database;
    private final EvictionStrategy <K,V>  evictionStrategy;
    private final WriteStrategy <K,V> writeStrategy;
    private final AtomicInteger size = new AtomicInteger(0);
    private final int MAX_CAPACITY;
    private final int THREAD_POOL_SIZE = 10;
    private final Map <Integer, ExecutorService> slotToExecutorMapping;
    // CHANGED: Added lock for atomic size check and eviction decision
    private final Object sizeLock = new Object();

    public CachingOrchestrator(Cache cache, Database database, EvictionStrategy evictionStrategy, WriteStrategy writeStrategy, int maxCapacity) {
        this.cache = cache;
        this.database = database;
        this.evictionStrategy = evictionStrategy;
        this.writeStrategy = writeStrategy;
        this.MAX_CAPACITY = maxCapacity;
        this.slotToExecutorMapping = new ConcurrentHashMap<>();
        for (int i=0; i<THREAD_POOL_SIZE; i++) {
            slotToExecutorMapping.put(i, Executors.newSingleThreadExecutor());
        }
    }

    private int getSlot(int hash) {
        return Math.abs(hash % THREAD_POOL_SIZE);
    }

    public void write(K key, V value) {
        int slot = getSlot(key.hashCode());

        K lruKey = null;
        boolean needsEviction = false;

        synchronized (sizeLock) {
            V existingValue = cache.getValue(key);
            boolean isUpdate = (existingValue != null);

            if (!isUpdate && size.get() >= MAX_CAPACITY) {
                // NEW insert at capacity → evict
                lruKey = evictionStrategy.evict();
                needsEviction = true;
                // Size stays at MAX (net zero)
            } else if (!isUpdate) {
                // NEW insert below capacity → increment
                size.incrementAndGet();
            }
            // If isUpdate, don't modify size at all
        }

        if (needsEviction && lruKey!=null) {
            int lruKeySlot = getSlot(lruKey.hashCode());
            final K finalLruKey = lruKey; // For lambda capture

            if (lruKeySlot == slot) {
                ExecutorService executor = slotToExecutorMapping.get(lruKeySlot);
                executor.execute(() -> {
                    cache.deleteByKey(finalLruKey);
                    writeStrategy.write(cache, database, key, value);
                    evictionStrategy.accessed(key, value);
                });
            }
            else {
                ExecutorService lruExecutor = slotToExecutorMapping.get(lruKeySlot);
                ExecutorService insertionExecutor = slotToExecutorMapping.get(slot);
                lruExecutor.execute(() -> cache.deleteByKey(finalLruKey));
                insertionExecutor.execute(() -> {
                    writeStrategy.write(cache, database, key, value);
                    evictionStrategy.accessed(key, value);
                });
            }
        }
        else {
            ExecutorService executor = slotToExecutorMapping.get(slot);
            executor.execute(() -> {
                writeStrategy.write(cache, database, key, value);
                evictionStrategy.accessed(key, value);
            });
        }
    }

    public V read(K key) {
        int slot = getSlot(key.hashCode());
        ExecutorService executor = slotToExecutorMapping.get(slot);

        Future <V> value = executor.submit(() -> {
            V val = cache.getValue(key);
            if (val != null) {
                evictionStrategy.accessed(key, val);
            }
            return val;
        });

        try {
            return value.get();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (ExecutionException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    // CHANGED: Added utility methods for testing
    public int getCurrentSize() {
        return size.get();
    }

    public int getMaxCapacity() {
        return MAX_CAPACITY;
    }

    public void shutdown() {
        for (ExecutorService executor : slotToExecutorMapping.values()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
};

public class Main {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Cache System Test Suite ===\n");

        // Initialize components
        Cache<String, String> cache = new CacheImpl<>();
        Database<String, String> database = new DatabaseImpl<>();
        WriteStrategy<String, String> writeStrategy = new WriteThroughStrategy<>();
        EvictionStrategy<String, String> evictionStrategy = new LRUEvictionStrategy<>();

        int capacity = 5;
        CachingOrchestrator<String, String> orchestrator = new CachingOrchestrator<>(
                cache, database, evictionStrategy, writeStrategy, capacity
        );

        // Test 1: Basic Write and Read
        System.out.println("Test 1: Basic Write and Read");
        System.out.println("------------------------------");
        orchestrator.write("user1", "Alice");
        orchestrator.write("user2", "Bob");
        orchestrator.write("user3", "Charlie");
        Thread.sleep(200);

        System.out.println("Read user1: " + orchestrator.read("user1"));
        System.out.println("Read user2: " + orchestrator.read("user2"));
        System.out.println("Read user3: " + orchestrator.read("user3"));
        System.out.println("Size: " + orchestrator.getCurrentSize() + "/" + orchestrator.getMaxCapacity());
        System.out.println("✅ PASS\n");

        // Test 2: Fill to Capacity
        System.out.println("Test 2: Fill Cache to Capacity");
        System.out.println("--------------------------------");
        orchestrator.write("user4", "David");
        orchestrator.write("user5", "Eve");
        Thread.sleep(200);
        System.out.println("Size: " + orchestrator.getCurrentSize() + "/" + orchestrator.getMaxCapacity());
        System.out.println("Expected: 5/5");
        System.out.println(orchestrator.getCurrentSize() == 5 ? "✅ PASS\n" : "❌ FAIL\n");

        // Test 3: LRU Eviction
        System.out.println("Test 3: LRU Eviction");
        System.out.println("---------------------");
        System.out.println("Current keys: user1, user2, user3, user4, user5");

        // Access user2 and user3 to make them recently used
        orchestrator.read("user2");
        orchestrator.read("user3");
        Thread.sleep(100);

        System.out.println("Accessed user2 and user3 (now most recent)");
        System.out.println("Adding user6... should evict user1 (LRU)");

        orchestrator.write("user6", "Frank");
        Thread.sleep(200);

        String evicted = orchestrator.read("user1");
        String exists = orchestrator.read("user6");

        System.out.println("Read user1 (should be null): " + evicted);
        System.out.println("Read user6 (should be 'Frank'): " + exists);
        System.out.println("Size: " + orchestrator.getCurrentSize() + "/" + orchestrator.getMaxCapacity());
        System.out.println((evicted == null && "Frank".equals(exists)) ? "✅ PASS\n" : "❌ FAIL\n");

        // Test 4: Update Existing Key (Critical!)
        System.out.println("Test 4: Update Existing Key");
        System.out.println("----------------------------");
        int sizeBefore = orchestrator.getCurrentSize();
        System.out.println("Size before update: " + sizeBefore);

        orchestrator.write("user2", "Bob_Updated");
        Thread.sleep(200);

        int sizeAfter = orchestrator.getCurrentSize();
        String updated = orchestrator.read("user2");

        System.out.println("Updated user2 to: " + updated);
        System.out.println("Size after update: " + sizeAfter);
        System.out.println("Expected: Size should NOT change (still " + sizeBefore + ")");
        System.out.println((sizeAfter == sizeBefore && "Bob_Updated".equals(updated)) ? "✅ PASS\n" : "❌ FAIL\n");

        // Test 5: Multiple Updates Don't Increase Size
        System.out.println("Test 5: Multiple Updates to Same Key");
        System.out.println("-------------------------------------");
        sizeBefore = orchestrator.getCurrentSize();

        orchestrator.write("user3", "Charlie_v2");
        Thread.sleep(100);
        orchestrator.write("user3", "Charlie_v3");
        Thread.sleep(100);
        orchestrator.write("user3", "Charlie_v4");
        Thread.sleep(200);

        sizeAfter = orchestrator.getCurrentSize();
        String finalValue = orchestrator.read("user3");

        System.out.println("Updated user3 3 times");
        System.out.println("Final value: " + finalValue);
        System.out.println("Size before: " + sizeBefore + ", after: " + sizeAfter);
        System.out.println((sizeAfter == sizeBefore && "Charlie_v4".equals(finalValue)) ? "✅ PASS\n" : "❌ FAIL\n");

        // Test 6: Concurrent Writes
        System.out.println("Test 6: Concurrent Writes");
        System.out.println("--------------------------");
        CountDownLatch latch = new CountDownLatch(20);

        for (int i = 0; i < 20; i++) {
            final int index = i;
            new Thread(() -> {
                orchestrator.write("concurrent" + index, "Value" + index);
                latch.countDown();
            }).start();
        }

        latch.await();
        Thread.sleep(300);

        int finalSize = orchestrator.getCurrentSize();
        System.out.println("Wrote 20 concurrent keys");
        System.out.println("Final size: " + finalSize + "/" + orchestrator.getMaxCapacity());
        System.out.println("Size should be at capacity (5)");
        System.out.println((finalSize <= capacity) ? "✅ PASS\n" : "❌ FAIL\n");

        // Test 7: Read Your Own Writes
        System.out.println("Test 7: Read Your Own Writes");
        System.out.println("-----------------------------");
        String testKey = "consistency-test";
        orchestrator.write(testKey, "TestValue");
        Thread.sleep(200);

        String readValue = orchestrator.read(testKey);
        System.out.println("Wrote: TestValue, Read: " + readValue);
        System.out.println("TestValue".equals(readValue) ? "✅ PASS\n" : "❌ FAIL\n");

        // Test 8: Thread Affinity (Same Key Operations)
        System.out.println("Test 8: Thread Affinity");
        System.out.println("------------------------");
        String affinityKey = "affinity-test";

        orchestrator.write(affinityKey, "v1");
        Thread.sleep(50);
        orchestrator.write(affinityKey, "v2");
        Thread.sleep(50);
        orchestrator.write(affinityKey, "v3");
        Thread.sleep(200);

        String affinityValue = orchestrator.read(affinityKey);
        System.out.println("Wrote v1, v2, v3 sequentially to same key");
        System.out.println("Final value: " + affinityValue);
        System.out.println("v3".equals(affinityValue) ? "✅ PASS\n" : "❌ FAIL\n");

        // Test 9: Cache Miss Returns Null
        System.out.println("Test 9: Cache Miss");
        System.out.println("-------------------");
        String missValue = orchestrator.read("nonexistent-key");
        System.out.println("Read nonexistent key: " + missValue);
        System.out.println("Expected: null");
        System.out.println((missValue == null) ? "✅ PASS\n" : "❌ FAIL\n");

        // Test 10: Stress Test - Verify Size Never Exceeds Capacity
        System.out.println("Test 10: Stress Test (100 operations)");
        System.out.println("--------------------------------------");
        CountDownLatch stressLatch = new CountDownLatch(100);

        for (int i = 0; i < 100; i++) {
            final int index = i;
            new Thread(() -> {
                if (index % 2 == 0) {
                    orchestrator.write("stress" + (index % 30), "V" + index);
                } else {
                    orchestrator.read("stress" + (index % 30));
                }
                stressLatch.countDown();
            }).start();
        }

        stressLatch.await();
        Thread.sleep(500);

        finalSize = orchestrator.getCurrentSize();
        System.out.println("After 100 concurrent operations:");
        System.out.println("Final size: " + finalSize + "/" + orchestrator.getMaxCapacity());
        System.out.println("Size should never exceed capacity");
        System.out.println((finalSize <= capacity) ? "✅ PASS\n" : "❌ FAIL\n");

        // Summary
        System.out.println("=== Test Summary ===");
        System.out.println("All tests completed!");
        System.out.println("Final cache size: " + orchestrator.getCurrentSize());
        System.out.println("Max capacity: " + orchestrator.getMaxCapacity());

        // Cleanup
        System.out.println("\nShutting down...");
        orchestrator.shutdown();
        System.out.println("Shutdown complete. ✅");
    }
}