import java.util.*;

enum ProductType {
    BEVERAGE, SNACK
};

// Factory for products can be added on need basis

class Product {
    private final String id;
    private final String name;
    private final ProductType productType;
    private final double price;

    public Product(String id, String name, ProductType productType, double price) {
        if (id == null || name == null || productType == null || price < 0) throw new IllegalArgumentException("Invalid parameter(s) passed for product");
        this.id = id;
        this.name = name;
        this.productType = productType;
        this.price = price;
    }

    public String getId() {
        return id;
    }

    public double getPrice() {
        return price;
    }

    @Override
    public String toString() {
        return String.format("Product: id: %s name: %s type: %s price: %.2f", id, name, productType, price);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Product)) return false;
        Product other = (Product) obj;
        return other.id.equals(this.id);
    }

};

class InventoryItem {
    private final Product product;
    private int quantity;

    public InventoryItem(Product product, int quantity) {
        if (product == null || quantity < 0) throw new IllegalArgumentException("Invalid parameter(s) passed for inventory item");
        this.product = product;
        this.quantity = quantity;
    }

    // Add getters and setters as per necessary

    public int getQuantity() {
        return quantity;
    }

    public void incrementQuantity() {
        quantity+=1;
    }

    public void decrementQuantity() {
        quantity-=1;
    }

    @Override
    public String toString() {
        return product.toString() + " quantity: "+ quantity;
    }

    public double getPrice() {
        return product.getPrice();
    }
};

class StateResult {
    private final boolean success;
    private final String message;
    private final VendingMachineState next;

    public StateResult(boolean success, String message, VendingMachineState next) {
        if (message == null || next == null) throw new IllegalArgumentException("Invalid parameters passed for state result");
        this.success = success;
        this.message = message;
        this.next = next;
    }

    public boolean isSuccess() {
        return success;
    }

    public VendingMachineState getNext() {
        return next;
    }

    public String getMessage() {
        return message;
    }

};

interface VendingMachineState {
    default boolean isOperational() {
        return true;
    }
    public StateResult run(VendingMachineController vendingMachineController);
};

class MaintenanceState implements VendingMachineState {
    @Override
    public boolean isOperational() {
        return false;
    }

    @Override
    public StateResult run(VendingMachineController vendingMachineController) {
        return new StateResult(false, "Machine in maintenance mode", this);
    }
};

class HomeState implements VendingMachineState {
    @Override
    public StateResult run(VendingMachineController vendingMachineController) {
        System.out.println("Welcome to home state, printing product catalog");
        vendingMachineController.printCatalog();
        return new StateResult(true, "Transitioning to selection state", new ProductSelectionState());
    }
};

class Input {
    private final static Scanner scanner = new Scanner(System.in);

    public static String readString(String prompt) {
        System.out.println(prompt);
        return scanner.nextLine().trim();
    }

    public void close() {
        scanner.close();
    }
};

class ProductSelectionState implements VendingMachineState {
    private final int MAX_ATTEMPTS = 3;
    @Override
    public StateResult run(VendingMachineController vendingMachineController) {
        for (int i=0; i<MAX_ATTEMPTS; i++) {
            String productId = Input.readString("Please enter the product ID to be selected");
            // CHANGED: Add exit option
            if (productId.equalsIgnoreCase("EXIT")) {
                return new StateResult(true, "Thank you for using our vending machine", new HomeState());
            }

            if (!vendingMachineController.validateProduct(productId)) {
                System.out.println("Invalid product ID, please retry");
            }
            else if (!vendingMachineController.checkProductAvailability(productId)) {
                System.out.println("Selected product not in stock, please retry with different product");
            }
            else {
                // Since the vending machine is running on a single thread, we don't need to check and lock the product
                vendingMachineController.setSelectedProductId(productId);
                return new StateResult(true, "Transitioning to payment state", new PaymentState());
            }
        }
        return new StateResult(false, "Max attempts reached with invalid product", new HomeState());
    }
};

enum PaymentMethod {
    CREDIT_CARD, UPI
};

class PaymentState implements VendingMachineState {
    private final int MAX_PAYMENT_INPUT_ATTEMPTS = 3;
    private final int MAX_PAYMENT_STATUS_VALIDATION_ATTEMPTS = 3;

    private PaymentStrategy parsePaymentMethodInput(String input) {
        return switch(input) {
            case "UPI" -> new UPIPaymentStrategy();
            case "CREDIT_CARD" -> new CreditCardPaymentStrategy();
            default -> null;
        };
    }

    @Override
    public StateResult run(VendingMachineController vendingMachineController) {
        double price = vendingMachineController.getSelectedProductPrice();
        System.out.printf("Amount to pay: %.2f", price);

        boolean isPaymentMethodInputValid = false;
        PaymentStrategy paymentStrategy = null;

        for (int i=0; i<MAX_PAYMENT_INPUT_ATTEMPTS; i++) {
            String userInput = Input.readString("Please enter your preferred payment method: (UPI/CREDIT_CARD)");
            paymentStrategy = parsePaymentMethodInput(userInput);
            if (paymentStrategy == null) {
                System.out.println("Invalid input, please try again");
            }
            else {
                isPaymentMethodInputValid = true;
                break;
            }
        }

        if (!isPaymentMethodInputValid) return new StateResult(false, "Max attempts reached for payment method input", new HomeState());

        for (int i=0; i<MAX_PAYMENT_STATUS_VALIDATION_ATTEMPTS; i++) {
            boolean paymentStatus = paymentStrategy.pay(price);
            if (paymentStatus) return new StateResult(true, "Transitioning to dispensing state", new DispensingState());
        }

        return new StateResult(false, "Max attempts reached for payment status validation", new HomeState());
    }
};

class DispensingState implements VendingMachineState {
    @Override
    public StateResult run(VendingMachineController vendingMachineController) {
        System.out.println("Dispensing your product");
        vendingMachineController.decrementSelectedProductStock();
        System.out.println("Please collect your product, thanks!");
        vendingMachineController.clearSelectedProduct();
        return new StateResult(true, "Going back to home state", new HomeState());
    }
};

interface PaymentStrategy {
    public boolean pay(double amount);
};

class UPIPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean pay(double amount) {
        return true;
    }
};

class CreditCardPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean pay(double amount) {
        return true;
    }
};

class VendingMachineController {
    private VendingMachineState currentState;
    private final Map <String, InventoryItem> productCatalog = new HashMap<>();
    private String selectedProductId;

    public void setSelectedProductId(String productId) {
        this.selectedProductId = productId;
    }

    public void clearSelectedProduct() {
        this.selectedProductId = null;
    }

    public void decrementSelectedProductStock() {
        if (selectedProductId != null && productCatalog.containsKey(selectedProductId)) {
            productCatalog.get(selectedProductId).decrementQuantity();
            // todo: can be done with removeif method in map
            if (productCatalog.get(selectedProductId).getQuantity() == 0) productCatalog.remove(selectedProductId);
        }
    }

    public void addToCatalog(Product product) {
        String productId = product.getId();
        // TODO: Think of a 1 liner
        productCatalog.putIfAbsent(productId, new InventoryItem(product, 0));
        productCatalog.get(productId).incrementQuantity();
    }

    public String getSelectedProductId() {
        return selectedProductId;
    }

    public double getSelectedProductPrice() {
        if (selectedProductId!=null && productCatalog.containsKey(selectedProductId)) {
            return productCatalog.get(selectedProductId).getPrice();
        }
        return 0.0;
    }

    public void printCatalog() {
        productCatalog.values().stream().forEach(inventory -> System.out.println(inventory));
    }

    public boolean validateProduct(String id) {
        return productCatalog.containsKey(id);
    }

    public boolean checkProductAvailability(String id) {
        return validateProduct(id) && productCatalog.get(id)!=null && productCatalog.get(id).getQuantity() > 0;
    }

    public VendingMachineController() {
        this.currentState = new HomeState();
    }

    public void run() {
        while (true) {
            if (!currentState.isOperational()) {
                System.out.println("Machine in maintenance mode, please try again after sometime");
                break;
            }
            StateResult stateResult = currentState.run(this);
            if (!stateResult.isSuccess()) System.out.println("Something went wrong during execution of current state");
            if (stateResult.getMessage()!=null) System.out.println("Message from vending machine: "+ stateResult.getMessage());
            currentState = stateResult.getNext();
        }
    }
};

public class Main {
    public static void main(String[] args) {
        // CHANGED: Add initialization
        VendingMachineController controller = new VendingMachineController();

        // Add sample products
        controller.addToCatalog(new Product("P001", "Coca Cola", ProductType.BEVERAGE, 2.50));
        controller.addToCatalog(new Product("P001", "Coca Cola", ProductType.BEVERAGE, 2.50));
        controller.addToCatalog(new Product("P002", "Pepsi", ProductType.BEVERAGE, 2.50));
        controller.addToCatalog(new Product("P003", "Chips", ProductType.SNACK, 1.50));
        controller.addToCatalog(new Product("P004", "Cookies", ProductType.SNACK, 2.00));

        // Run the vending machine
        controller.run();
    }
}