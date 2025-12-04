import java.util.*;
import java.util.concurrent.*;

interface Cache <K,V> {
    public V getByKey(K key);
    public void put(K key, V value);
    public void delete(K key);
}

class CacheImpl <K,V> implements Cache <K,V> {
    private final Map <K, V> keyValueMappings = new ConcurrentHashMap<>();

    @Override
    public void put(K key, V value) {
        keyValueMappings.put(key, value);
    }

    @Overide
    public V getByKey(K key) {
        return keyValueMappings.get(key);
    }

    @Override
    public void delete(K key) {
        keyValueMappings.remove(key);
    }
}

interface Database <K,V> {
    public void put(K key, V value);
    public V getByKey(K key);
    public void delete(K key);
}

class DatabaseImpl <K,V> implements Database <K,V> {
    private final Map <K, V> keyValueMappings = new ConcurrentHashMap<>();

    @Override
    public void write(K key, V value) {
        try {
            Thread.sleep(100);
            keyValueMappings.put(key, value);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public V getByKey(K key) {
        try {
            Thread.sleep(50);
            return keyValueMappings.get(key);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void delete(K key) {
        keyValueMappings.remove(key);
    }
}

interface WriteStrategy {
    public void write(Cache cache, Database database, K key, V value);
}

class WriteThroughStrategy implements WriteStrategy {
    @Override
    public void write(Cache cache, Database database, K key, V value) {
        CompletableFuture <Void> cacheWrite = cache.put(key, value);
        CompletableFuture <Void> databaseWrite = database.put(key, value);
        CompletableFuture.allOf(cacheWrite, databaseWrite).join();
    }
}

interface EvictionStrategy <K,V> {
    public void accessed(K key, V value);
    public K evict();
}

class DLLNode <K,V> {
    private final K key;
    private final V value;
    private DLLNode next;
    private DLLNode prev;

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

    public void setNext(DLLNode next) {
        this.next = next;
    }

    public void setPrev(DLLNode prev) {
        this.prev = prev;
    }

    public DLLNode getNext() {
        return next;
    }

    public DLLNode getPrev() {
        return prev;
    }
}

class DLL {
    private final DLLNode head = new DLLNode(null, null);
    private final DLLNode tail = new DLLNode(null, null);

    public DLL() {
        head.setNext(tail);
        tail.setPrev(head);
    }

    public void addFront(DLLNode node) {
        if (node == null) throw new IllegalArgumentException("Node to be added cannot be null");
        DLLNode headNext = head.getNext();
        head.setNext(node);
        node.setPrev(head);
        node.setNext(headNext);
        headNext.setPrev(node);
    }

    public void removeNode(DLLNode node) {
        if (node == null) throw new IllegalArgumentException("Node to be deleted cannot be null");
        DLLNode nodePrev = node.getPrev();
        DLLNode nodeNext = node.getNext();
        nodePrev.setNext(nodeNext);
        nodeNext.setPrev(nodePrev);
    }

    public boolean isEmpty() {
        return head.getNext() == tail && tail.getPrev() == head;
    }

    public DLLNode getTailPredecessor() {
        return tail.getPrev();
    }
}

class LRUEvictionStrategy <K,V> implements EvictionStrategy <K,V> {
    private final DLL dll = new DLL();
    private final Map <K, DLLNode> keyToNodeMappings = new ConcurrentHashMap<>();

    @Override
    public synchronized void accessed(K key, V value) {
        DLLNode node = keyToNodeMappings.get(key);
        if (node != null) {
            dll.removeNode(node);
            dll.addFront(node);
        }
    }

    @Override
    public synchronized K evict() {
        if (dll.isEmpty()) return null;
        DLLNode tailPredecessor = dll.getTailPredecessor();
        dll.removeNode(tailPredecessor);
        return tailPredecessor.getKey();
    }
}

class CacheController <K,V> {
    private final Cache cache;
    private final Database database;
    private EvictionStrategy evictionStrategy;
    private WriteStrategy writeStrategy;
    private final int THREAD_POOL_SIZE = 10;
    private final AtomicInteger currentSize = 0;
    private final Map <K, ExecutorService> keyToExecutorMapping;
    private final long capacity;

    public CacheController(Cache cache, Database database, EvictionStrategy evictionStrategy, WriteStrategy writeStrategy, long capacity) {
        if (cache == null || database == null || evictionStrategy == null || writeStrategy == null) throw new IllegalArgumentException("Invalid argument(s) passed for cache controller");
        this.cache = cache;
        this.database = database;
        this.evictionStrategy = evictionStrategy;
        this.writeStrategy = writeStrategy;
        this.capacity = capacity;
        this.keyToExecutorMapping = new ConcurrentHashMap<>();
        for (int i=0; i<THREAD_POOL_SIZE; i++) keyToExecutorMapping.put(i, Executors.newSingleThreadExecutor());
    }

    public int getExecutorIndex(K key) {
        return Math.abs(key.hashCode()) % THREAD_POOL_SIZE;
    }

    public void write(K key, V value) {
        // carefully handle race conditions here, between the time of checking the size and incrementing it/decrmenting it, other threads can come into play

        // Two possible cases: write a new key-value pair if existing size less than capacity, a new write which needs eviction first
        boolean evictionRequired = false;

        synchronized(this) {
            if (currentSize.get() < capacity) {
                // Optimistically reserve a slot
                currentSize.incrementAndGet();
            }
            // in case of eviction, an element will be removed and then added. to avoid complicated increment and decrment logic inside a synchronized block, not making any change
            else evictionRequired = true;
        }

        int writeExecutorIndex = getExecutorIndex(key);
        ExecutorService writeExecutor = keyToExecutorMapping.get(writeExecutorIndex);

        if (evictionRequired) {
            // TODO: Don't forget to use the accessed method
            K evictKey = evictionStrategy.evict();
            int evictionExecutorIndex = getExecutorIndex(evictKey);

            if (writeExecutorIndex == evictionExecutorIndex) {
                writeExecutor.execute(() -> {
                    cache.delete(evictKey);
                    writeStrategy.write(cache, database, key, value);
                    evictionStrategy.accessed(key, value);
                });
            }
            else {
                ExecutorService evictExecutor = keyToExecutorMapping.get(evictionExecutorIndex);
                evictExecutor.execute(() -> cache.delete(evictKey));
                writeExecutor.execute(() -> {
                    writeStrategy.write(cache, database, key, value);
                    evictionStrategy.accessed(key, value);
                });
            }
        }

        else {
            writeExecutor.execute(() -> {
                writeStrategy.write(cache, database, key, value);
                evictionStrategy.accessed(key, value);
            });
        }
    }

    public V getByKey(K key) {
        int readExecutorIndex = getExecutorIndex(key);
        ExecutorService readExecutor = keyToExecutorMapping.get(readExecutorIndex);

        Future <V> cachedValueFuture = readExecutor.submit(() -> {
            V cachedValue = cache.getByKey(key);
            if (cachedValue != null) return cachedValue;
            return database.getByKey(key);
        });

        try {
            return cachedValueFuture.get();
        }
        catch (InterruptedException ex) {
            // maybe print some logs
            return null;
        }
    }
}


public class Main {
    public static void main(String [] args) {




    }
}