import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class Producer {
    private final String id;

    public Producer(String id) {
        this.id = id;
    }

    public void push(Topic topic, String data) {
        if (topic == null) throw new IllegalArgumentException("Non-null topic required by consumer");
        System.out.println("Producer with ID: " + id + " pushing data: " + data + " to topic: "+ topic.getId());
        topic.push(data);
    }
};

class Consumer {
    private final String id;
    private final List <Partition> partitions;

    public Consumer(String id) {
        this.id = id;
        this.partitions = new CopyOnWriteArrayList<>();
    }

    public void listen() {
        if (partitions == null) return ;
        for (Partition partition: partitions) {
            if (!partition.isEmpty()) {
                System.out.println("Consumer with ID: "+ id+ " got message from partition: " + partition.getId() + " Message: " + partition.poll());
            }
        }
    }

    public void setPartitions(List <Partition> partitions) {
        if (partitions == null) throw new IllegalArgumentException("Consumer cannot have null partitions");
        /* CHANGE: Previously it was this.partitions = new ArrayList<>(partitions), which is problematic because of two reasons:
            1. Reassignment to final variable
            2. Assigning an array list (a non thread-safe data structure) : concurrent modification while iteration on the data structure will lead to Concurrent Modification Exception
         */
        this.partitions.clear();
        this.partitions.addAll(partitions);
    }
};

class ConsumerGroup {
    private final String id;
    private final List <Consumer> consumers;

    public ConsumerGroup(String id, List <Consumer> consumers) {
        this.id = id;
        this.consumers = new ArrayList<>(consumers);
    }

    public List<Consumer> getConsumers() {
        return List.copyOf(consumers);
    }

    public String getId() {
        return id;
    }
};

interface ConsumerDivisionStrategy {
    public Map<Consumer, List<Partition>> divide(List <Consumer> consumers, List <Partition> partitions);
};

class RandomConsumerDivisionStrategy implements ConsumerDivisionStrategy {
    @Override
    public Map<Consumer, List <Partition>> divide(List <Consumer> consumers, List <Partition> partitions) {
        if (consumers == null || partitions == null) throw new IllegalArgumentException("Invalid arguments passed to RandomConsumerDivisionStrategy");
        int n = consumers.size();

        Map <Consumer, List<Partition>> consumerToPartitionMappings = new ConcurrentHashMap<>();

        for (Partition partition: partitions) {
            int pick = ThreadLocalRandom.current().nextInt(n); // CHANGED
            Consumer consumer = consumers.get(pick); // CHANGED
            consumerToPartitionMappings.computeIfAbsent(consumer, k -> new ArrayList<>()).add(partition);
        }
        return consumerToPartitionMappings;
    }
};

class Topic {
    private final String id;
    private final List <Partition> partitions; // VERY IMP: Please note that these are the base partitions
    private final List <ConsumerGroup> consumerGroups;
    private final Map <ConsumerGroup, List<Partition>> virtualPartitionsMap = new ConcurrentHashMap<>(); // Holds list of all virtual partitions like CG1: [CG1-P1, CG1-P2,CG1-P3]
    private final PartitionStrategy partitionStrategy;
    private final ConsumerDivisionStrategy consumerDivisionStrategy;

    public Topic(String id, List <Partition> partitions, List <ConsumerGroup> consumerGroups, PartitionStrategy partitionStrategy, ConsumerDivisionStrategy consumerDivisionStrategy) {
        if (partitions == null || partitions.isEmpty()) throw new IllegalArgumentException("Topic cannot have empty partitions");
        if (consumerGroups == null || consumerGroups.isEmpty()) throw new IllegalArgumentException("Topic cannot have empty consumer groups");
        if (partitionStrategy == null) throw new IllegalArgumentException("Partition strategy cannot be null");
        if (consumerDivisionStrategy == null) throw new IllegalArgumentException("Consumer division strategy cannot be null");

        this.id = id;
        this.partitions = new ArrayList<>(partitions);
        this.consumerGroups = new ArrayList<>(consumerGroups);
        this.partitionStrategy = partitionStrategy;
        this.consumerDivisionStrategy = consumerDivisionStrategy;
        assignPartitionsToConsumers();
    }

    /*
        CHANGE: Bug present earlier
        Let's say there are Siebel and BRM consumer groups
        Siebel S1 consumer and BRM B1 register themselves with partition P1
        Now if S1 does a poll on P1 (let's say x), it effectively removed an element from the queue
        Element x is not available for BRM B1 consumer

        Hence, we need to create virtual partitions Like S1 - P1 and B1 - P1
        So, that both S1 and B1 have access to the same elements
     */

    private void assignPartitionsToConsumers() {
        for (ConsumerGroup consumerGroup: consumerGroups) {
            List <Consumer> consumers = consumerGroup.getConsumers();
            List <Partition> virtualPartitions = new ArrayList<>();

            // Creating virtual partitions like P1, CG1  P2, CG1   P3, CG1
            for (Partition p: partitions) {
                virtualPartitions.add(new Partition(p.getId() + "@" + consumerGroup.getId()));
            }
            virtualPartitionsMap.put(consumerGroup, virtualPartitions);

            Map<Consumer, List<Partition>> consumerToPartitionMappings = consumerDivisionStrategy.divide(Collections.unmodifiableList(consumers), Collections.unmodifiableList(virtualPartitions));
            for (Consumer consumer: consumerToPartitionMappings.keySet()) {
                List <Partition> partitions = consumerToPartitionMappings.get(consumer);
                if (partitions!=null) consumer.setPartitions(partitions);
            }
        }
    }

    private Partition getPartition() {
        return partitionStrategy.assign(Collections.unmodifiableList(partitions));
    }

    public String getId() {
        return id;
    }

    public void push(String data) {
        Partition partition = getPartition();
        // CHANGE: Push data to all the virtual partisions eg: CG1-P1, CG2-P1, CG3-P1 etc
        int idx = partitions.indexOf(partition);
        if (idx < 0) throw new IllegalStateException("Partition not found");
        for (List <Partition> consumerGroup: virtualPartitionsMap.values()) {
            consumerGroup.get(idx).add(data);
        }
    }
};

class Partition {
    private final String id;
    private final Queue <String> buffer = new ConcurrentLinkedQueue<>();

    public Partition(String id) {
        this.id = id;
    }

    public void add(String data) {
        buffer.add(data);
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    public String poll() {
        return buffer.poll();
    }

    public String getId() {
        return id;
    }
};

class KafkaBroker {
    private final String id;
    private final Set <Producer> producers;
    private final Map <Topic, Set<Producer>> topicToProducersMapping;

    public KafkaBroker(String id, List <Producer> producers, List <ConsumerGroup> consumerGroups) {
        this.id = id;
        this.producers = new HashSet<>(producers);
        this.topicToProducersMapping = new ConcurrentHashMap<>();
    }

    public void registerTopic(Topic topic, Set<Producer> allowedProducers) {
        this.topicToProducersMapping.put(topic, new HashSet<>(allowedProducers));
    }

    public void push(Producer producer, Topic topic, String data) {
        if (!producers.contains(producer)) throw new IllegalArgumentException("Producer not managed by Kafka broker");
        if (!topicToProducersMapping.containsKey(topic)) throw new IllegalArgumentException("Topic not managed by Kafka broker");
        if (!topicToProducersMapping.get(topic).contains(producer)) throw new UnsupportedOperationException("Producer not authorized to push to topic");

        producer.push(topic, data);
    }
};

interface PartitionStrategy {
    public Partition assign(List <Partition> partitions);
};

class RandomPartitionStrategy implements PartitionStrategy {
      @Override
      public Partition assign(List <Partition> partitions) {
            if (partitions == null || partitions.isEmpty()) throw new IllegalArgumentException("Partition list cannot be empty");
            int idx = ThreadLocalRandom.current().nextInt(partitions.size()); // CHANGED
            return partitions.get(idx); // CHANGED
      }
};

public class Main {
    public static void main(String[] args) throws InterruptedException {
        // Strategies
        PartitionStrategy partitionStrategy = new RandomPartitionStrategy();
        ConsumerDivisionStrategy divisionStrategy = new RandomConsumerDivisionStrategy();

        // Base partitions for the topic
        List<Partition> basePartitions = Arrays.asList(
                new Partition("p-0"),
                new Partition("p-1"),
                new Partition("p-2")
        );

        // Consumers and groups
        Consumer s1 = new Consumer("S1");
        Consumer s2 = new Consumer("S2");
        ConsumerGroup siebel = new ConsumerGroup("SIEBEL", Arrays.asList(s1, s2));

        Consumer b1 = new Consumer("B1");
        Consumer b2 = new Consumer("B2");
        ConsumerGroup brm = new ConsumerGroup("BRM", Arrays.asList(b1, b2));

        // Topic
        Topic orders = new Topic(
                "orders",
                basePartitions,
                Arrays.asList(siebel, brm),
                partitionStrategy,
                divisionStrategy
        );

        // Producers and broker
        Producer p1 = new Producer("P1");
        Producer p2 = new Producer("P2");

        KafkaBroker broker = new KafkaBroker("broker-1", Arrays.asList(p1, p2), Arrays.asList(siebel, brm));
        broker.registerTopic(orders, new HashSet<>(Arrays.asList(p1, p2)));

        // Consumers polling in parallel
        ExecutorService consumerPool = Executors.newFixedThreadPool(4);
        AtomicBoolean running = new AtomicBoolean(true);
        consumerPool.submit(() -> { while (running.get()) { s1.listen(); try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }});
        consumerPool.submit(() -> { while (running.get()) { s2.listen(); try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }});
        consumerPool.submit(() -> { while (running.get()) { b1.listen(); try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }});
        consumerPool.submit(() -> { while (running.get()) { b2.listen(); try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }});

        // Producers pushing concurrently
        ExecutorService producerPool = Executors.newFixedThreadPool(2);
        producerPool.submit(() -> {
            for (int i = 1; i <= 10; i++) {
                broker.push(p1, orders, "order-" + i + "-from-P1");
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        producerPool.submit(() -> {
            for (int i = 1; i <= 10; i++) {
                broker.push(p2, orders, "order-" + i + "-from-P2");
                try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });

        // Let it run briefly
        Thread.sleep(3000);

        // Shutdown
        running.set(false);
        producerPool.shutdownNow();
        consumerPool.shutdownNow();
        producerPool.awaitTermination(1, TimeUnit.SECONDS);
        consumerPool.awaitTermination(1, TimeUnit.SECONDS);
    }
}