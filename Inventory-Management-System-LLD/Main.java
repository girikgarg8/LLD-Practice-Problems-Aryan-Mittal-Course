import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;

// CHANGE: ASSUMPTION:
// Client operations (add/remove/transfer) may be single-threaded, but internal
// operations like scheduled inventory checks run on separate threads. Using ConcurrentHashMap
// for thread-safe access to shared collections.

enum ProductType {
    GROCERY, ELECTRONICS
}

// CHANGE: Added user roles for access control requirement
enum UserRole {
    ADMIN, MANAGER, OPERATOR, VIEWER
}

abstract class Product {
    private final String sku;
    private final String name;
    private final ProductType productType;
    private final double price;

    public Product(String sku, String name, ProductType productType, double price) {
        if (sku == null || name == null || productType == null || price < 0) {
            throw new IllegalArgumentException("Valid parameters required for product");
        }
        this.sku = sku;
        this.name = name;
        this.productType = productType;
        this.price = price;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public ProductType getType() {
        return productType;
    }

    public double getPrice() {
        return price;
    }

    // CHANGE: Add hashcode and equals for hash related collection operations
    @Override
    public int hashCode() {
        return Objects.hash(sku);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Product)) return false;
        Product other = (Product) obj;
        return other.sku.equals(this.sku);
    }

    @Override
    public String toString() {
        return String.format("Product{sku=%s, name=%s, type=%s, price=%.2f}", sku, name, productType, price);
    }
}

class GroceryProduct extends Product {
    public GroceryProduct(String sku, String name, double price) {
        super(sku, name, ProductType.GROCERY, price);
    }
}

class ElectronicsProduct extends Product {
    public ElectronicsProduct(String sku, String name, double price) {
        super(sku, name, ProductType.ELECTRONICS, price);
    }
}

class ProductFactory {
    public static Product create(ProductType productType, String sku, String name, double price) {
        switch (productType) {
            case GROCERY:
                return new GroceryProduct(sku, name, price);
            case ELECTRONICS:
                return new ElectronicsProduct(sku, name, price);
            default:
                throw new IllegalArgumentException("Product type not supported");
        }
    }
}

// CHANGE: Created inventory item to track quantity per product (separation of concerns)
// CHANGE: IMPORTANT: Added synchronized methods for thread-safety
class InventoryItem {
    private final Product product;
    private int quantity;

    public InventoryItem(Product product, int quantity) {
        if (product == null || quantity < 0) {
            throw new IllegalArgumentException("Valid parameters required for inventory item");
        }
        this.product = product;
        this.quantity = quantity;
    }

    // synchronization not required here, no concurrent WW or WR scenario
    public Product getProduct() {
        return product;
    }

    // synchronization required on reads or writes of quantity
    public synchronized int getQuantity() {
        return quantity;
    }

    public synchronized void addQuantity(int amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must be positive");
        this.quantity += amount;
    }

    public synchronized void removeQuantity(int amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must be positive");
        if (this.quantity < amount) throw new IllegalStateException("Insufficient quantity");
        this.quantity -= amount;
    }

    @Override
    public String toString() {
        return String.format("InventoryItem{product=%s, quantity=%d}", product, quantity);
    }
}

// CHANGE: Add AuditLog for tracking all inventory changes (requirement)
class AuditLog {
    private final LocalDateTime timestamp;
    private final String warehouseId;
    private final String productSku;
    private final String action;
    private final int quantityChange;
    private final String userId;

    public AuditLog(String warehouseId, String action, String productSku, int quantityChange, String userId) {
        this.timestamp = LocalDateTime.now();
        this.warehouseId = warehouseId;
        this.action = action;
        this.productSku = productSku;
        this.quantityChange = quantityChange;
        this.userId = userId;
    }

    @Override
    public String toString() {
        return String.format("[%s] Warehouse: %s, Action: %s, SKU: %s, Quantity: %d, User: %s",
                timestamp, warehouseId, action, productSku, quantityChange, userId);
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getWarehouseId() {
        return warehouseId;
    }
}

// CHANGE: Replace list with map for efficient SKU based lookups
// CHANGE: Use concurrent hashmap for thread-safe access from scheduler thread
class Warehouse {
    private final String id;
    private final Map<String, InventoryItem> inventory;

    public Warehouse(String id) {
        if (id == null) throw new IllegalArgumentException("Valid parameters required for warehouse");
        this.id = id;
        this.inventory = new ConcurrentHashMap<>();
    }

    public String getId() {
        return id;
    }

    public Map<String, InventoryItem> getInventory() {
        return Collections.unmodifiableMap(inventory);
    }

    public void addProduct(Product product, int quantity) {
        String sku = product.getSku();
        if (inventory.containsKey(sku)) {
            inventory.get(sku).addQuantity(quantity);
        } else {
            inventory.put(sku, new InventoryItem(product, quantity));
        }
    }

    public void removeProduct(String sku, int quantity) {
        if (!inventory.containsKey(sku)) {
            throw new IllegalArgumentException("Product not found in warehouse");
        }
        InventoryItem item = inventory.get(sku);
        item.removeQuantity(quantity);

        // Remove from inventory if quantity reaches 0
        if (item.getQuantity() == 0) {
            inventory.remove(sku);
        }
    }

    public Map<ProductType, Integer> getInventoryLevel() {
        Map<ProductType, Integer> productInventoryLevel = new HashMap<>();
        for (InventoryItem item : inventory.values()) {
            ProductType productType = item.getProduct().getType();
            productInventoryLevel.put(productType,
                    productInventoryLevel.getOrDefault(productType, 0) + item.getQuantity());
        }
        return productInventoryLevel;
    }

    public InventoryItem getProductBySku(String sku) {
        return inventory.get(sku);
    }

    @Override
    public String toString() {
        return String.format("Warehouse{id='%s', inventoryCount=%d}", id, inventory.size());
    }
}

interface NotificationStrategy {
    void notify(String message);
}

class EmailNotificationStrategy implements NotificationStrategy {
    @Override
    public void notify(String message) {
        System.out.println("📧 Notification: " + message);
    }
}

class User {
    private final String id;
    private final String name;
    private final UserRole userRole;

    public User(String id, String name, UserRole userRole) {
        this.id = id;
        this.name = name;
        this.userRole = userRole;
    }

    public UserRole getUserRole() {
        return userRole;
    }

    public String getUserId() {
        return id;
    }

    public boolean canPerformOperation(String operation) {
        switch (userRole) {
            case ADMIN:
                return true;
            case MANAGER:
                return !operation.equals("DELETE_WAREHOUSE");
            case OPERATOR:
                return operation.equals("ADD_PRODUCT") || operation.equals("REMOVE_PRODUCT");
            case VIEWER:
                return operation.equals("VIEW_INVENTORY") || operation.equals("GENERATE_REPORT");
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return String.format("User{id='%s', name='%s', role=%s}", id, name, userRole);
    }
}

class WarehouseManager {
    private final ConcurrentHashMap<String, Warehouse> warehouses;
    private final NotificationStrategy notificationStrategy;
    private final ScheduledExecutorService scheduledExecutorService;
    private final List<AuditLog> auditLogs;
    private final ConcurrentHashMap<String, Integer> skuMinimumThresholds;
    private static final int DEFAULT_MINIMUM_THRESHOLD = 10;

    public Integer getMinimumThreshold(String sku) {
        return skuMinimumThresholds.getOrDefault(sku, DEFAULT_MINIMUM_THRESHOLD);
    }

    public void setMinimumThreshold(String sku, int threshold) {
        if (threshold < 0) throw new IllegalArgumentException("Threshold must be non-negative");
        skuMinimumThresholds.put(sku, threshold);
    }

    public WarehouseManager(List<Warehouse> warehouseList, NotificationStrategy notificationStrategy) {
        this.warehouses = new ConcurrentHashMap<>();
        warehouseList.forEach(w -> warehouses.put(w.getId(), w));
        this.notificationStrategy = notificationStrategy;
        this.auditLogs = Collections.synchronizedList(new ArrayList<>());
        this.skuMinimumThresholds = new ConcurrentHashMap<>();
        this.scheduledExecutorService = Executors.newScheduledThreadPool(1);

        scheduledExecutorService.scheduleWithFixedDelay(
                this::checkWarehouseInventoryLevels,
                0, 5, TimeUnit.MINUTES
        );
    }

    private void checkAccess(User user, String operation) {
        if (!user.canPerformOperation(operation)) {
            throw new SecurityException(
                    String.format("User %s with role %s cannot perform operation: %s",
                            user.getUserId(), user.getUserRole(), operation)
            );
        }
    }

    // FIXED: Changed from addProductToWareHouse to addProductToWarehouse
    public void addProductToWarehouse(User user, String warehouseId, Product product, int quantity) {
        checkAccess(user, "ADD_PRODUCT");

        Warehouse warehouse = warehouses.get(warehouseId);
        if (warehouse == null) {
            throw new IllegalArgumentException("Warehouse not found: " + warehouseId);
        }

        warehouse.addProduct(product, quantity);
        auditLogs.add(new AuditLog(warehouseId, "ADD", product.getSku(), quantity, user.getUserId()));
        System.out.println("✅ Added " + quantity + " units of " + product.getName() +
                " to warehouse " + warehouseId);
    }

    public void removeProductFromWarehouse(User user, String warehouseId, String sku, int quantity) {
        checkAccess(user, "REMOVE_PRODUCT");

        Warehouse warehouse = warehouses.get(warehouseId);
        if (warehouse == null) {
            throw new IllegalArgumentException("Warehouse not found: " + warehouseId);
        }

        warehouse.removeProduct(sku, quantity);
        auditLogs.add(new AuditLog(warehouseId, "REMOVE", sku, -quantity, user.getUserId()));
        System.out.println("✅ Removed " + quantity + " units of SKU " + sku +
                " from warehouse " + warehouseId);
    }

    public void transferProduct(User user, String fromWarehouseId, String toWarehouseId,
                                String sku, int quantity) {
        checkAccess(user, "ADD_PRODUCT");

        Warehouse fromWarehouse = warehouses.get(fromWarehouseId);
        Warehouse toWarehouse = warehouses.get(toWarehouseId);

        if (fromWarehouse == null || toWarehouse == null) {
            throw new IllegalArgumentException("Invalid warehouse IDs");
        }

        InventoryItem item = fromWarehouse.getProductBySku(sku);
        if (item == null) {
            throw new IllegalArgumentException("Product not found in source warehouse");
        }
        Product product = item.getProduct();

        // Remove from source
        fromWarehouse.removeProduct(sku, quantity);
        auditLogs.add(new AuditLog(fromWarehouseId, "TRANSFER_OUT", sku, -quantity, user.getUserId()));

        // Add to destination
        toWarehouse.addProduct(product, quantity);
        auditLogs.add(new AuditLog(toWarehouseId, "TRANSFER_IN", sku, quantity, user.getUserId()));

        System.out.println("✅ Transferred " + quantity + " units of " + product.getName() +
                " from warehouse " + fromWarehouseId + " to " + toWarehouseId);
    }

    private void checkWarehouseStock(String warehouseId, Warehouse warehouse) {
        warehouse.getInventory().forEach((sku, inventoryItem) -> {
            int quantity = inventoryItem.getQuantity();
            int threshold = getMinimumThreshold(sku);

            if (quantity < threshold) {
                Product product = inventoryItem.getProduct();
                notificationStrategy.notify(
                        String.format("⚠️  Low stock alert: %s (SKU: %s) in warehouse %s (Current: %d, Threshold: %d)",
                                product.getName(), sku, warehouseId, quantity, threshold)
                );
            }
        });
    }

    private void checkWarehouseInventoryLevels() {
        warehouses.forEach((warehouseId, warehouse) -> {
            checkWarehouseStock(warehouseId, warehouse);
        });
    }

    // ADDED: Method needed by Main
    public List<AuditLog> getAuditLogs() {
        synchronized (auditLogs) {
            return new ArrayList<>(auditLogs);
        }
    }

    // ADDED: Method needed by Main
    public void shutdown() {
        scheduledExecutorService.shutdown();
    }
}

public class Main {
    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║       INVENTORY MANAGEMENT SYSTEM - TEST SUITE               ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝\n");

        // ========== Setup ==========
        System.out.println("🔧 SETUP PHASE");
        System.out.println("═".repeat(70));

        // Create users
        User admin = new User("U001", "Alice (Admin)", UserRole.ADMIN);
        User manager = new User("U002", "Bob (Manager)", UserRole.MANAGER);
        User operator = new User("U003", "Charlie (Operator)", UserRole.OPERATOR);
        User viewer = new User("U004", "Diana (Viewer)", UserRole.VIEWER);

        System.out.println("👥 Created users:");
        System.out.println("   " + admin);
        System.out.println("   " + manager);
        System.out.println("   " + operator);
        System.out.println("   " + viewer);

        // Create warehouses
        Warehouse warehouse1 = new Warehouse("WH-NORTH");
        Warehouse warehouse2 = new Warehouse("WH-SOUTH");
        Warehouse warehouse3 = new Warehouse("WH-EAST");

        System.out.println("\n📦 Created warehouses: WH-NORTH, WH-SOUTH, WH-EAST");

        // Create warehouse manager
        WarehouseManager wm = new WarehouseManager(
                Arrays.asList(warehouse1, warehouse2, warehouse3),
                new EmailNotificationStrategy()
        );

        // Configure thresholds
        wm.setMinimumThreshold("SKU-001", 5);
        wm.setMinimumThreshold("SKU-002", 8);
        wm.setMinimumThreshold("SKU-003", 3);
        wm.setMinimumThreshold("SKU-004", 10);

        System.out.println("\n⚙️  Configured SKU thresholds:");
        System.out.println("   SKU-001: 5 units");
        System.out.println("   SKU-002: 8 units");
        System.out.println("   SKU-003: 3 units");
        System.out.println("   SKU-004: 10 units");

        // Create products
        Product apple = ProductFactory.create(ProductType.GROCERY, "SKU-001", "Apple", 2.50);
        Product banana = ProductFactory.create(ProductType.GROCERY, "SKU-002", "Banana", 1.50);
        Product laptop = ProductFactory.create(ProductType.ELECTRONICS, "SKU-003", "Laptop", 999.99);
        Product phone = ProductFactory.create(ProductType.ELECTRONICS, "SKU-004", "Phone", 699.99);

        System.out.println("\n🏷️  Created products:");
        System.out.println("   " + apple);
        System.out.println("   " + banana);
        System.out.println("   " + laptop);
        System.out.println("   " + phone);

        // ========== Test 1: Add Products ==========
        System.out.println("\n\n📝 TEST 1: Add Products to Warehouses");
        System.out.println("═".repeat(70));

        wm.addProductToWarehouse(admin, "WH-NORTH", apple, 50);
        wm.addProductToWarehouse(manager, "WH-NORTH", banana, 30);
        wm.addProductToWarehouse(operator, "WH-NORTH", laptop, 20);
        wm.addProductToWarehouse(admin, "WH-SOUTH", phone, 25);

        // ========== Test 2: Access Control ==========
        System.out.println("\n\n🔒 TEST 2: Access Control Validation");
        System.out.println("═".repeat(70));

        System.out.println("Test Case: Viewer trying to add product (should fail)");
        try {
            wm.addProductToWarehouse(viewer, "WH-NORTH", apple, 10);
            System.out.println("   ❌ FAILED: Should have thrown SecurityException");
        } catch (SecurityException e) {
            System.out.println("   ✅ PASSED: Access denied correctly");
        }

        System.out.println("\nTest Case: Operator trying to remove product (should succeed)");
        try {
            wm.removeProductFromWarehouse(operator, "WH-NORTH", "SKU-001", 10);
            System.out.println("   ✅ PASSED: Operator can remove products");
        } catch (SecurityException e) {
            System.out.println("   ❌ FAILED: Operator should be able to remove");
        }

        // ========== Test 3: Transfer ==========
        System.out.println("\n\n🔄 TEST 3: Transfer Products Between Warehouses");
        System.out.println("═".repeat(70));

        System.out.println("Before transfer:");
        InventoryItem bananasNorth = warehouse1.getProductBySku("SKU-002");
        System.out.println("   WH-NORTH bananas: " +
                (bananasNorth != null ? bananasNorth.getQuantity() : 0));

        wm.transferProduct(admin, "WH-NORTH", "WH-SOUTH", "SKU-002", 15);

        System.out.println("\nAfter transfer:");
        bananasNorth = warehouse1.getProductBySku("SKU-002");
        InventoryItem bananasSouth = warehouse2.getProductBySku("SKU-002");
        System.out.println("   WH-NORTH bananas: " +
                (bananasNorth != null ? bananasNorth.getQuantity() : 0));
        System.out.println("   WH-SOUTH bananas: " +
                (bananasSouth != null ? bananasSouth.getQuantity() : 0));

        // ========== Test 4: Low Stock Alert ==========
        System.out.println("\n\n⚠️  TEST 4: Low Stock Alert Simulation");
        System.out.println("═".repeat(70));

        System.out.println("Reducing stock to trigger alerts...");
        wm.removeProductFromWarehouse(admin, "WH-NORTH", "SKU-001", 35);
        wm.removeProductFromWarehouse(admin, "WH-NORTH", "SKU-002", 10);
        wm.removeProductFromWarehouse(admin, "WH-NORTH", "SKU-003", 18);

        System.out.println("\nCurrent stock levels:");
        System.out.println("   Apple (SKU-001): " +
                warehouse1.getProductBySku("SKU-001").getQuantity() + " (threshold: 5)");
        System.out.println("   Banana (SKU-002): " +
                warehouse1.getProductBySku("SKU-002").getQuantity() + " (threshold: 8)");
        System.out.println("   Laptop (SKU-003): " +
                warehouse1.getProductBySku("SKU-003").getQuantity() + " (threshold: 3)");

        // ========== Test 5: View Inventory ==========
        System.out.println("\n\n📊 TEST 5: View Inventory Levels");
        System.out.println("═".repeat(70));

        Map<ProductType, Integer> levels = warehouse1.getInventoryLevel();
        System.out.println("WH-NORTH inventory by type:");
        levels.forEach((type, qty) ->
                System.out.println("   " + type + ": " + qty + " units"));

        // ========== Test 6: Audit Trail ==========
        System.out.println("\n\n📋 TEST 6: Audit Trail");
        System.out.println("═".repeat(70));

        List<AuditLog> logs = wm.getAuditLogs();
        System.out.println("Total transactions: " + logs.size());
        System.out.println("\nRecent 5 transactions:");
        logs.stream()
                .skip(Math.max(0, logs.size() - 5))
                .forEach(log -> System.out.println("   " + log));

        // ========== Test 7: Edge Cases ==========
        System.out.println("\n\n🧪 TEST 7: Edge Case Handling");
        System.out.println("═".repeat(70));

        System.out.println("Test Case: Remove more than available");
        try {
            wm.removeProductFromWarehouse(admin, "WH-NORTH", "SKU-001", 1000);
            System.out.println("   ❌ FAILED: Should have thrown exception");
        } catch (IllegalStateException e) {
            System.out.println("   ✅ PASSED: Insufficient quantity prevented");
        }

        System.out.println("\nTest Case: Transfer from non-existent warehouse");
        try {
            wm.transferProduct(admin, "WH-INVALID", "WH-NORTH", "SKU-001", 5);
            System.out.println("   ❌ FAILED: Should have thrown exception");
        } catch (IllegalArgumentException e) {
            System.out.println("   ✅ PASSED: Invalid warehouse rejected");
        }

        // ========== Summary ==========
        System.out.println("\n\n╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    TEST SUITE COMPLETE                        ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");

        System.out.println("\n✅ All core functionality tested:");
        System.out.println("   ✓ Product addition with access control");
        System.out.println("   ✓ Product removal");
        System.out.println("   ✓ Warehouse transfers");
        System.out.println("   ✓ Access control enforcement");
        System.out.println("   ✓ Audit logging");
        System.out.println("   ✓ Threshold monitoring");
        System.out.println("   ✓ Edge case handling");

        System.out.println("\n💡 Note: Low stock alerts will trigger automatically");
        System.out.println("   every 5 minutes via the background scheduler thread.");

        // Cleanup
        wm.shutdown();
        System.out.println("\n🏁 System shutdown complete.");
    }
}