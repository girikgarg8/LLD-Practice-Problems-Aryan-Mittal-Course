import java.util.*;
import java.util.concurrent.*;

enum ProductType {
    ELECTRONICS, GROCERY
}

abstract class Product {
    private final String id;
    private final String name;
    private final int price;
    private final ProductType productType;
    private int quantity;

    public Product(ProductType productType, String id, String name, int price, int quantity) {
        if (id == null || name == null || productType == null) throw new IllegalArgumentException("Id,name and type should be non-null for product");
        if (price <= 0 || quantity <= 0) throw new IllegalArgumentException("Product's price and quantity should be positive");
        this.id = id;
        this.name = name;
        this.productType = productType;
        this.price = price;
        this.quantity = quantity;
    }

    public String getId() {
        return id;
    }

    // Making the methods for increment and check of quantity as synchronized - to avoid race conditions between auditing thread trying to check quantity and client thread trying to increment/decrement the quantity
    public synchronized int getQuantity() {
        return quantity;
    }

    public synchronized boolean decreaseQuantity(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("Quantity to be removed should be positive");
        // UPDATE: Add validation to check if the asked number of product quantity is present.
        if (amount > quantity) return false;
        quantity -= amount;
        return true;
    }

    public synchronized void increaseQuantity(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("Quantity to be added should be positive");
        quantity += amount;
    }

    public ProductType getProductType() {
        return this.productType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || this.getClass() != obj.getClass()) return false;
        Product other = (Product) obj;
        return this.id.equals(other.id);
    }

    //UPDATE: Add toString for better debugging
    @Override
    public String toString() {
        return String.format("Product {id=%s, name=%s, type=%s, price=%d, quantity=%d", id, name, productType, price, quantity);
    }
}

class GroceryProduct extends Product {
    public GroceryProduct(String id, String name, int price, int quantity) {
        super(ProductType.GROCERY, id, name, price, quantity);
    }
}

class ElectronicsProduct extends Product {
    public ElectronicsProduct(String id, String name, int price, int quantity) {
        super(ProductType.ELECTRONICS, id, name, price, quantity);
    }
}

class ProductFactory {
    public static Product create(ProductType productType, String id, String name, int price, int quantity) {
        // UPDATE: Add null check, good defensive programming
        if (productType == null) throw new IllegalArgumentException("Product type cannot be null");

        switch(productType) {
            case GROCERY: return new GroceryProduct(id, name, price, quantity);
            case ELECTRONICS: return new ElectronicsProduct(id, name, price, quantity);
            default: throw new IllegalArgumentException("Product type not supported: "+ productType);
        }
    }
}

class Warehouse {
    private final String id;
    private final Map <String, Product> skuToProductMap;

    public Warehouse(String id) {
        // UPDATE: Add check for empty string
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Warehouse ID cannot be null or empty");
        this.id = id;
        skuToProductMap = new ConcurrentHashMap<>();
    }

    public void addProduct(Product product) {
        if (product == null) throw new IllegalArgumentException("Product can't be null");
        String productId = product.getId();
        // UPDATE: Use Map compute method to have expression for value
        skuToProductMap.compute(productId, (key, existingProduct) -> {
            if (existingProduct == null) return product;
            else {
                existingProduct.increaseQuantity(product.getQuantity()); // UPDATE: Increment the quantity by amount specified in the product object than hardcding it to 1
                return existingProduct;
            }
        });
    }

    // UPDATE: Make the method generic, to remove the specified quantity, instead of hardcoding to removing 1 unit
    public boolean removeProduct(String productId, int amount) {
        // UPDATE: Add check for null or empty product ID
        if (productId == null || productId.trim().isEmpty()) throw new IllegalArgumentException("Product ID cannot be null or empty");
        // UPDATE: Add check for negative quantity
        if (amount <= 0) throw new IllegalArgumentException("Quantity must be positive");

        Product product = skuToProductMap.get(productId);

        if (product == null) return false;
        // decrease quantity output status will indicate if the required quantity is there for removal
        boolean success = product.decreaseQuantity(amount);
        if (!success) return false;
        if (product.getQuantity() == 0) skuToProductMap.remove(productId);
        return true;
    }

    public Map <String, Integer> getInventoryCount() {
        Map <String, Integer> skuToQuantityMap = new HashMap<>();
        for (Map.Entry <String, Product> entry: skuToProductMap.entrySet()) {
            skuToQuantityMap.put(entry.getKey(), entry.getValue().getQuantity());
        }
        return skuToQuantityMap;
    }

    public String getId() {
        return id;
    }

    // UPDATE: Add method to get total products count
    public int getTotalProducts() {
        return skuToProductMap.size();
    }
}

class WarehouseManager {
    private final List <Warehouse> warehouses;
    // UPDATE: Set the thread name to "WareHouseAuditor". Also, set it as daemon thread since it is performing a background job
    // Also, make the audit interval configurable
    private final ScheduledExecutorService scheduler;
    private final NotificationStrategy notificationStrategy;
    // UPDATE: Make threshold map configurable instead of hardcoding
    private final Map <String, Integer> productThresholdMap;
    private static final int DEFAULT_PRODUCT_THRESHOLD = 5;

    public WarehouseManager(NotificationStrategy notificationStrategy, Map <String, Integer> customThresholds, int auditIntervalMinutes) {
        if (notificationStrategy == null) throw new IllegalArgumentException("Notification strategy cannot be null");
        if (auditIntervalMinutes <= 0) throw new IllegalArgumentException("Audit interval must be positive, got: "+ auditIntervalMinutes);
        validateThresholds(customThresholds);

        this.notificationStrategy = notificationStrategy;
        this.warehouses = new CopyOnWriteArrayList<>(); // Making it copy on write array list, since the audit thread might iterate over it at the same of adding a new warehouse in the list, which can lead to Concurrent Modification Exception
        this.productThresholdMap = new HashMap<>(customThresholds);

        this.scheduler =  Executors.newScheduledThreadPool(1, r-> {
            Thread thread = new Thread(r, "WarehouseAuditor");
            thread.setDaemon(true);
            return thread;
        });

        scheduler.scheduleAtFixedRate(this::checkWarehouseStockLevels, 1, auditIntervalMinutes, TimeUnit.MINUTES);
    }

    private void validateThresholds(Map <String, Integer> thresholds) {
        if (thresholds == null || thresholds.isEmpty()) throw new IllegalArgumentException("Custom thresholds map cannot be empty");

        for (Map.Entry <String, Integer> entry: thresholds.entrySet()) {
            String productId = entry.getKey();
            Integer threshold = entry.getValue();

            if (productId == null || productId.trim().isEmpty()) throw new IllegalArgumentException("Product ID in threshiolds cannot be null or empty");
            if (threshold == null) throw new IllegalArgumentException("Threshold value cannot be null for product");

            if (threshold <= 0) throw new IllegalArgumentException("Threshold must be positibe for products");
        }

    }

    public Integer getThreshold(String productId) {
        return productThresholdMap.getOrDefault(productId, DEFAULT_PRODUCT_THRESHOLD);
    }

    public boolean isBelowThreshold(String productId, Integer quantity) {
        return quantity < getThreshold(productId);
    }

    private void checkWarehouseStockLevels() {
        for (Warehouse warehouse: warehouses) {
            Map <String, Integer> productInventoryCount = warehouse.getInventoryCount();
            productInventoryCount.entrySet().parallelStream().forEach(entry -> {
                String productId = entry.getKey();
                Integer quantity = entry.getValue();

                if (isBelowThreshold(productId, quantity)) {
                    notificationStrategy.notify("Product ID: " + productId + " below threshold in warehouse: " + warehouse.getId() + " current quantity: " + quantity);
                }
            });
        }
    }

    public void addWarehouse(Warehouse warehouse) {
        Objects.requireNonNull(warehouse, "Warehouse cannot be null");
        warehouses.add(warehouse);
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(15, TimeUnit.SECONDS)) scheduler.shutdownNow();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}

interface NotificationStrategy {
    public void notify(String message);
}

class EmailNotificationStrategy implements NotificationStrategy {
    @Override
    public void notify(String message) {
        System.out.println("Received notification via Email: " + message);
    }
}

class SMSNotificationStrategy implements NotificationStrategy {
    @Override
    public void notify(String message) {
        System.out.println("Received notification via SMS: " + message);
    }
}

public class Main {
    public static void main(String[] args) {
        System.out.println("=== Inventory Management System - Test Suite ===\n");

        try {
            // Test 1: Create products
            System.out.println("Test 1: Creating products...");
            Product laptop = ProductFactory.create(ProductType.ELECTRONICS, "1400071", "Laptop", 1000, 15);
            Product phone = ProductFactory.create(ProductType.ELECTRONICS, "1400072", "Phone", 500, 8);
            Product apple = ProductFactory.create(ProductType.GROCERY, "2000001", "Apple", 2, 100);
            Product milk = ProductFactory.create(ProductType.GROCERY, "2000002", "Milk", 3, 50);
            System.out.println("✅ Products created: " + laptop);
            System.out.println();

            // Test 2: Create warehouses
            System.out.println("Test 2: Creating warehouses...");
            Warehouse warehouse1 = new Warehouse("WH-001");
            Warehouse warehouse2 = new Warehouse("WH-002");
            System.out.println("✅ Warehouses created: WH-001, WH-002");
            System.out.println();

            // Test 3: Add products to warehouses
            System.out.println("Test 3: Adding products to warehouses...");
            warehouse1.addProduct(laptop);
            warehouse1.addProduct(phone);
            warehouse1.addProduct(apple);

            warehouse2.addProduct(ProductFactory.create(ProductType.ELECTRONICS, "1400071", "Laptop", 1000, 3));
            warehouse2.addProduct(milk);

            displayInventory("WH-001", warehouse1);
            displayInventory("WH-002", warehouse2);
            System.out.println();

            // Test 4: Remove products
            System.out.println("Test 4: Removing products...");
            boolean removed = warehouse1.removeProduct("1400071", 5);
            System.out.println("Removed 5 laptops from WH-001: " + (removed ? "Success" : "Failed"));

            removed = warehouse1.removeProduct("2000001", 50);
            System.out.println("Removed 50 apples from WH-001: " + (removed ? "Success" : "Failed"));

            displayInventory("WH-001", warehouse1);
            System.out.println();

            // Test 5: Test insufficient quantity
            System.out.println("Test 5: Testing insufficient quantity...");
            removed = warehouse1.removeProduct("1400072", 100);
            System.out.println("Tried to remove 100 phones (only 8 available): " + (removed ? "Success" : "Failed (Expected)"));
            displayInventory("WH-001", warehouse1);
            System.out.println();

            // Test 6: Test concurrent operations
            System.out.println("Test 6: Testing concurrent operations...");
            testConcurrentOperations(warehouse1, laptop);
            displayInventory("WH-001", warehouse1);
            System.out.println();

            // Test 7: Set up warehouse manager
            System.out.println("Test 7: Setting up WarehouseManager...");
            Map<String, Integer> thresholds = new HashMap<>();
            thresholds.put("1400071", 12);
            thresholds.put("1400072", 15);
            thresholds.put("2000001", 30);

            NotificationStrategy emailStrategy = new EmailNotificationStrategy();
            WarehouseManager manager = new WarehouseManager(emailStrategy, thresholds, 1);

            manager.addWarehouse(warehouse1);
            manager.addWarehouse(warehouse2);
            System.out.println("✅ WarehouseManager initialized with 1-minute audit interval");
            System.out.println();

            // Test 8: Create low stock conditions
            System.out.println("Test 8: Creating low stock conditions...");
            warehouse1.removeProduct("1400071", 8); // Laptop: 15-5-8 = 2 (below threshold of 12)
            warehouse2.removeProduct("1400071", 2); // Laptop: 3-2 = 1 (below threshold of 12)
            warehouse1.removeProduct("2000001", 25); // Apple: 100-50-25 = 25 (below threshold of 30)

            System.out.println("✅ Low stock conditions created:");
            displayInventory("WH-001", warehouse1);
            displayInventory("WH-002", warehouse2);
            System.out.println();

            // Test 9: Wait for audit
            System.out.println("Test 9: Waiting for scheduled audit (70 seconds)...");
            System.out.println("Expected notifications for:");
            System.out.println("  - Laptop (1400071) in both warehouses");
            System.out.println("  - Apple (2000001) in WH-001");
            System.out.println();
            Thread.sleep(70000);

            // Test 10: Edge cases
            System.out.println("\nTest 10: Testing edge cases...");

            // Non-existent product
            removed = warehouse1.removeProduct("INVALID-ID", 1);
            System.out.println("Remove non-existent product: " + (removed ? "Success" : "Failed (Expected)"));

            // Zero quantity removal
            try {
                warehouse1.removeProduct("1400071", 0);
                System.out.println("❌ Should have thrown exception for zero quantity");
            } catch (IllegalArgumentException e) {
                System.out.println("✅ Caught invalid quantity: " + e.getMessage());
            }

            // Negative quantity
            try {
                warehouse1.removeProduct("1400071", -5);
                System.out.println("❌ Should have thrown exception for negative quantity");
            } catch (IllegalArgumentException e) {
                System.out.println("✅ Caught negative quantity: " + e.getMessage());
            }

            // Null product
            try {
                warehouse1.addProduct(null);
                System.out.println("❌ Should have thrown exception for null product");
            } catch (IllegalArgumentException e) {
                System.out.println("✅ Caught null product: " + e.getMessage());
            }
            System.out.println();

            // Test 11: Product equality
            System.out.println("Test 11: Testing product equality...");
            Product laptop1 = ProductFactory.create(ProductType.ELECTRONICS, "1400071", "Laptop", 1000, 5);
            Product laptop2 = ProductFactory.create(ProductType.ELECTRONICS, "1400071", "Laptop", 1000, 10);
            Product grocery1 = ProductFactory.create(ProductType.GROCERY, "1400071", "Item", 100, 5);

            System.out.println("Electronics(1400071) == Electronics(1400071): " + laptop1.equals(laptop2) + " (Expected: true)");
            System.out.println("Electronics(1400071) == Grocery(1400071): " + laptop1.equals(grocery1) + " (Expected: false)");
            System.out.println();

            // Final inventory
            System.out.println("=== Final Inventory Report ===");
            displayInventory("WH-001", warehouse1);
            displayInventory("WH-002", warehouse2);

            // Cleanup
            System.out.println("\n=== Shutting Down ===");
            manager.shutdown();
            System.out.println("✅ All tests completed successfully!");

        } catch (Exception e) {
            System.err.println("❌ Error during testing: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void displayInventory(String warehouseName, Warehouse warehouse) {
        System.out.println("Warehouse " + warehouseName + " Inventory:");
        Map<String, Integer> inventory = warehouse.getInventoryCount();
        if (inventory.isEmpty()) {
            System.out.println("  (empty)");
        } else {
            for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
                System.out.println("  - Product " + entry.getKey() + ": " + entry.getValue() + " units");
            }
        }
    }

    private static void testConcurrentOperations(Warehouse warehouse, Product product) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(100);

        // 50 threads adding products
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                try {
                    Product newProduct = ProductFactory.create(
                            product.getProductType(),
                            product.getId(),
                            "Laptop",
                            1000,
                            1
                    );
                    warehouse.addProduct(newProduct);
                } finally {
                    latch.countDown();
                }
            });
        }

        // 50 threads removing products
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                try {
                    warehouse.removeProduct(product.getId(), 1);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("✅ Concurrent operations completed successfully");
    }
}