import java.util.*;
import java.util.concurrent.*;


// CHANGE: ASSUMPTION:
//Client operations (add/remove/transfer) may be single-threaded, but internal
//  operations like scheduled inventory checks run on separate threads. Using ConcurrentHashMap
// for thread-safe access to shared collections.

enum ProductType {
    GROCERY, ELECTRONICS
};

// CHANGE: Added user roles for access control requirement
enum UserRole {
    ADMIN, MANAGER, OPERATOR, VIEWER
};


abstract class Product {
    private final String sku;
    private final String name;
    private final ProductType productType;
    private final double price;

    public Product(String id, String name, ProductType productType, double price) {
        if (sku == null || name == null || productType == null || price < 0) throw new IllegalArgumentException("Valid parameters required for product");
        this.sku = sku;
        this.name = name;
        this.productType = productType;
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

    //CHANGE: Add hashcode and equals for hash related collection operations
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
        return String.format("Product {sku=%s, name=%s, type=%s, price=%.2f}", sku, name, productType, price);
    }
};

class GroceryProduct extends Product {
    public GroceryProduct(String id, String name, double price) {
        super(id, name, ProductType.GROCERY, price);
    }
};

class ElectronicsProduct extends Product {
    public ElectronicsProduct(String id, String name, double price) {
        super(id, name, ProductType.ELECTRONICS, price);
    }
};

class ProductFactory {
    public static Product create(ProductType productType, String id, String name, double price) {
        switch (productType) {
            case GROCERY: return new GroceryProduct(id, name, price);
            case ELECTRONICS: return new ElectronicsProduct(id, name, price);
            default: throw new IllegalArgumentException("Product type not supported");
        }
    }
};

// CHANGE: Created inventory item to track quantity per product (separation of concersn)
// CHANGE: IMPORTANT: Added synchronized methods for thread-safety (there's a possibility of the inventory check thread to run concurrently with the client thread to update the quantity, potentially causing a race condition)
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

    // synchronization not required here, no concurrent WW or WR scenario here
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
        return String.format("InventoryItem {product=%s, quantity=%d", product, quantity);
    }
};

// CHANGE: Add AuditLog for tracking all inventory changes (requirement)
class AuditLog {
    private final LocalDateTime timestamp;
    private final String warehouseId;
    private final String action;
    private final int quantityChange;
    private final String userId;

    public AuditLog(String warehouseId, String action, String productSku, int quantityChange, String userId) {
        this.timestamp = LocalDateTime.now();
        this.warehouseId = warehouseId;
        this.action = action;
        this.productSku = productSky;
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
};

// CHANGE: Replace list with map for efficient SKU based lookups
// CHANGE: Use concurrent hashmap for thread-safe access from scheduler thread
class Warehouse {
    private final String id;
    private final Map <String, InventoryItem> inventory;

    public Warehouse(String id) {
        if (id == null) throw new IllegalArgumentException("Valid parameters required for warehouse");
        this.id = id;
        this.inventory = new ConcurrentHashMap<>();
    }

    public String getId() {
        return id;
    }

    public Map <String, InventoryItem> getInventory() {
        return Collections.unmodifiableMap(inventory);
    }

    public void addProduct(Product product, int quantity) {
        String sku = product.getSku();
        if (inventory.containsKey(sky)) {
            inventory.get(sku).addQuantity(quantity);
        }
        else inventory.put(sku, new InventoryItem(product, quantity));
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
        for (InventoryItem item: inventory.values()) {
            ProductType productType = item.getProduct().getType();
            productInventoryLevel.put(productType, productInventoryLevel.getOrDefault(productType, 0) + item.getQuantity());
        }
    }

    @Override
    public String toString() {
        return String.format("Warehouse{id='%s', inventoryCount=%d}", id, inventory.size());
    }
};

interface NotificationStrategy {
    public void notify(String message);
};

class EmailNotificationStrategy implements NotificationStrategy {
    @Override
    public void notify(String message) {
        System.out.println("Received message by email: "+ message);
    }
};

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
        switch(userRole) {
            case ADMIN: return true; // admin can do everything
            case MANAGER: return !operator.equals("DELETE_WAREHOUSE"); // Manager can't delete warehouses
            case OPERATOR: return operation.equals("ADD_PRODUCT") || operation.equals("REMOVE_PRODUCT");
            case VIEWER: return operation.equals("VIEW_INVENTORY") || operation.equals("GENERATE_REPORT");
            default: return false;
        }
    }

    @Override
    public String toString() {
        return String.format("User{id='%s', name='%s', role=%s}", id, name, userRole);
    }
};

class WareHouseManager {
    private final ConcurrentHashMap <String, Warehouse> warehouses; // CHANGED: ConcurrentHashMap for thread safety
    private final NotificationStrategy notificationStrategy;
    private final ScheduledExecutorService scheduledExecutorService;
    private final List <AuditLog> auditLogs;

    private final ConcurrentHashMap<String, Integer> skuMinimumThresholds;
    private static final int DEFAULT_MINIMUM_THRESHOLD = 10; // default if sku not configured

    public String getMinimumThrehsold(String sku) {
        return skuMinimumThresholds.getOrDefault(sku, DEFAULT_MINIMUM_THRESHOLD);
    }

    public void setMinimumThreshold(String sku, int threshold) {
        if (threshold < 0) throw new IllegalArgumentException("Threshold must be non negative");
        skuMinimumThresholds.put(sku, threshold);
    }

    public WarehouseManager(List <Warehouse> warehouseList, NotificationStrategy notificationStrategy) {
        this.warehouses = new ConcurrentHashMap<>();
        warehouseList.forEach(w -> warehouses.put(w.getId(), w));
        this.notificationStrategy = notificationStrategy;
        // CHANGE: Thread safe list wrapper for audit logs
        this.auditLogs = Collections.synchronizedList(new ArrayList<>());

        // TODO: Begin here
    }

    public Integer getMinimumThreshold(ProductType productType) {
        return productMinimumThresholdMap.get(productType);
    }

    public WareHouseManager(List <Warehouse> warehouses, NotificationStrategy notificationStrategy) {
        this.warehouses = new ArrayList<>(warehouses);
        this.notificationStrategy = notificationStrategy;
        scheduledExecutorService = Executors.newScheduledThreadPool(1);

        scheduledExecutorService.scheduleWithFixedDelay(() -> checkWarehouseInventoryLevels(), 0, 5, TimeUnit.MINUTES);
    }

    private boolean isProductStockDeficit(ProductType productType, Integer inventoryLevel) {
        return inventoryLevel < getMinimumThreshold(productType);
    }

    private void checkWarehouseStock (Map <ProductType, Integer> warehouseStockLevels) {
        for (Map.Entry <ProductType, Integer> productInventory: warehouseStockLevels.entrySet()) {
            ProductType productType = productInventory.getKey();
            Integer inventoryLevel = productInventory.getValue();
            if (isProductStockDeficit(productType, inventoryLevel)) {
                notificationStrategy.notify("Deficit stock found for product type: "+ productType + " in warehouse ID: " + warehouse.getId());
            }
        }
    }

    private void checkWarehouseInventoryLevels() {
        warehouses.parallelStream().forEach(warehouse -> {
            Map.Entry <ProductType, Integer> warehouseStockLevels = warehouse.getInventoryLevel();
            checkWarehouseStock(warehouseStockLevels);
        });
    }

};

public class Main {
    public static void main(String [] args) {



    }
};