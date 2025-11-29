import java.util.*;
import java.io.ByteArrayInputStream; // UPDATED: Included for client side main method testing
import java.nio.charset.StandardCharsets; // UPDATED: Included for client side main method testing

class StateResult {
    private final VendingMachineState next;
    private final boolean success;
    private final String message;

    public StateResult(VendingMachineState next, boolean success, String message) {
        if (next == null || message == null || message.trim().isEmpty())
            throw new IllegalArgumentException("Null or empty parameter(s) passed for state result");

        this.next = next;
        this.success = success;
        this.message = message;
    }

    public boolean getSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public VendingMachineState getNext() {
        return next;
    }
}

interface VendingMachineState {
    public StateResult execute(VendingMachineController vendingMachineController);
    default public boolean isOperational() {
        return true;
    }
}

class MaintenanceState implements VendingMachineState {
    @Override
    public StateResult execute(VendingMachineController vendingMachineController) {
        return new StateResult(new MaintenanceState(), false, "Machine in maintenance mode, unoperational right now");
    }

    @Override
    public boolean isOperational() {
        return false;
    }
}

class HomeState implements VendingMachineState {
    @Override
    public StateResult execute(VendingMachineController vendingMachineController) {
        Map <String, Product> inventory = vendingMachineController.getProducts();
        // UPDATE: Add check for empty inventory
        if (inventory.isEmpty()) {
            return new StateResult(new HomeState(), false, "No products available. Please restock or enter maintenance");
        }

        System.out.println("List of available products is:");
        inventory.values().stream().forEach(System.out::println);
        return new StateResult(new SelectionState(), true, "Transitioning to selection state");
    }
}

class SelectionState implements VendingMachineState {

    @Override
    public StateResult execute(VendingMachineController vendingMachineController) {
        Map <String, Product> inventory = vendingMachineController.getProducts();
        String productId = vendingMachineController.inputLine("Enter product ID you wish to purchase");
        Product product = inventory.get(productId);
        if (product == null) return new StateResult(new SelectionState(), false, "Product ID not found in catalog");

        Integer quantity = vendingMachineController.readInteger("Enter quantity you wish to purchase");
        if (quantity == null) return new StateResult(new SelectionState(), false, "Invalid input for quantity");
        if (quantity <= 0) return new StateResult(new SelectionState(), false, "Quantity should be positive");
        if (product.getQuantity() < quantity) return new StateResult(new SelectionState(), false, "Quantity selected is greater than available stock");

        vendingMachineController.setSelectedProduct(product);
        vendingMachineController.setSelectedProductQuantity(quantity);
        return new StateResult(new PaymentState(), true, "Transitioning to payment state");
    }
}

interface PaymentStrategy {
    public boolean pay(long amount);
}

class UPIPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean pay(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Cannot process payment for amount less than or equal to zero");
        System.out.println("Paying amount: "+ amount + " by UPI");
        return true;
    }
}

class CreditCardPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean pay(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Cannot process payment for amount less than or equal to zero");
        System.out.println("Paying amount: "+ amount + " by credit card");
        return true;
    }
}

class PaymentState implements VendingMachineState {

    private PaymentStrategy parsePaymentMethodInput(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.trim().isEmpty()) return null;

        switch (paymentMethod.trim().toUpperCase()) {
            case "UPI": return new UPIPaymentStrategy();
            case "CREDIT_CARD": return new CreditCardPaymentStrategy();
            default: return null;
        }
    }

    @Override
    public StateResult execute(VendingMachineController vendingMachineController) {
        Product selectedProduct = vendingMachineController.getSelectedProduct();
        if (selectedProduct == null) return new StateResult(new SelectionState(), false, "Selected product is null, redirecting to selection state");
        int selectedProductQuantity = vendingMachineController.getSelectedProductQuantity();
        if (selectedProductQuantity == 0) return new StateResult(new SelectionState(), false, "Selected product quantity is 0, redirecting to selection state");

        long totalAmount = selectedProduct.getPrice() * selectedProductQuantity;

        String paymentMethod = vendingMachineController.inputLine("Enter your preferred payment method: UPI/CREDIT_CARD for amount: "+ totalAmount);
        PaymentStrategy paymentStrategy = parsePaymentMethodInput(paymentMethod);
        // UPDATE: Add null check for paymentStrategy, since parsePaymentMethodInput can return null
        if (paymentStrategy == null) return new StateResult(new PaymentState(), false, "Invalid payment method input, please retry");
        if (paymentStrategy.pay(totalAmount)) return new StateResult(new DispensingState(), true, "Payment successful, transitioning to dispensing state");
        else return new StateResult(new PaymentState(), false, "Payment unsuccessful, please retry");
    }
}

class DispensingState implements VendingMachineState {
    @Override
    public StateResult execute(VendingMachineController vendingMachineController) {
        Product selectedProduct = vendingMachineController.getSelectedProduct();
        if (selectedProduct == null)
            return new StateResult(new SelectionState(), false, "Selected product is null, redirecting to selection state");
        int selectedProductQuantity = vendingMachineController.getSelectedProductQuantity();
        if (selectedProductQuantity == 0)
            return new StateResult(new SelectionState(), false, "Selected product quantity is 0, redirecting to selection state");

        vendingMachineController.decrementStock(selectedProduct, selectedProductQuantity);

        // UPDATE: Clear selection after dispensing
        vendingMachineController.setSelectedProduct(null);
        vendingMachineController.setSelectedProductQuantity(0);

        return new StateResult(new HomeState(), true, "Successfully dispensed the product, redirecting to home state");
    }
}

enum ProductType {
    BEVERAGE, SNACK
}

abstract class Product {
    private final String id;
    private final String name;
    private final ProductType type;
    private final long price;
    private int quantity;

    public Product(String id, String name, ProductType type, long price, int quantity) {
        if (id == null || id.trim().isEmpty() || name == null || name.trim().isEmpty() || type == null)
            throw new IllegalArgumentException("Null or empty parameter(s) passed for product");

        if (price <= 0) throw new IllegalArgumentException("Product price has to be positive");
        if (quantity <= 0)  throw new IllegalArgumentException("Product quantity has to be positive");

        this.id = id;
        this.name = name;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
    }

    @Override
    public String toString() {
        return "Product {" + "id = " + id + " , name = " + name + ", type = " + type + " ,price = " + price + ", quantity = " + quantity + " }";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Product)) return false;
        Product other = (Product) obj;
        return this.id.equals(other.id) && this.type == other.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type);
    }

    public int getQuantity() {
        return quantity;
    }

    public long getPrice() {
        return price;
    }

    public String getId() {
        return id;
    }

    public void setQuantity(int quantity) {
        if (quantity <= 0)  throw new IllegalArgumentException("Product quantity has to be positive");
        this.quantity = quantity;
    }
}

class BeverageProduct extends Product {
    public BeverageProduct(String id, String name, long price, int quantity) {
        super(id, name, ProductType.BEVERAGE, price, quantity);
    }
}

class SnackProduct extends Product {
    public SnackProduct(String id, String name, long price, int quantity) {
        super(id, name, ProductType.SNACK, price, quantity);
    }
}


class VendingMachineController {
    private final Map <String, Product> inventory;
    private VendingMachineState currentState;
    private Product selectedProduct;
    private int selectedProductQuantity;
    private final Scanner scanner;

    // UPDATE: Scanner is hardwired to System.in; add an injectable Scanner or IO abstraction so you can simulate inputs for tests.
    public VendingMachineController(List <Product> products, Scanner scanner) {
        if (products == null || scanner == null) throw new IllegalArgumentException("Invalid arguments in vending machine controller");
        this.inventory = new HashMap<>();
        products.stream().forEach(product -> inventory.put(product.getId(), product));
        this.scanner = scanner;
        this.currentState = new HomeState();
    }

    public VendingMachineController(List <Product> products) {
        this(products, new Scanner(System.in)); // UPDATE: Delegate to injectable constructor
    }

    // UPDATE: Define method to execute a single transition

    public StateResult runOnce() {
        StateResult result = currentState.execute(this);
        System.out.println("State execution successful: " + result.getSuccess() + " Message: "+ result.getMessage());
        currentState = result.getNext();
        return result;
    }

    public void run() {
        while (true) {
           runOnce();
        }
    }

    public void run(int steps) {
        for (int i=0; i<steps; i++) {
            runOnce();
        }
    }

    public void setSelectedProduct(Product product) {
        // UPDATE: Allow selected product to be set as null (in case: we want to clear the state)
        this.selectedProduct = product;
    }

    public Product getSelectedProduct() {
        return selectedProduct;
    }

    public void setSelectedProductQuantity(int quantity) {
        // UPDATE: Allow clearing selected quantity with 0
        if (quantity < 0) throw new IllegalArgumentException("Selected product quantity should be non-negative");
        this.selectedProductQuantity = quantity;
    }

    public int getSelectedProductQuantity() {
        return selectedProductQuantity;
    }

    public void setMaintenance(boolean maintenance) {
        if (maintenance) currentState = new MaintenanceState();
        else currentState = new HomeState();
    }

    public Map <String, Product> getProducts() {
        return new HashMap<>(inventory);
    }

    public String inputLine(String prompt) {
        System.out.println(prompt);
        return scanner.nextLine();
    }

    public Integer readInteger(String prompt) {
        System.out.println(prompt);
        try {
            String input = scanner.nextLine();
            return Integer.parseInt(input.trim());
        }
        catch (Exception ex) { // specifically, expecting a number format exception
            return null;
        }
    }

    public void decrementStock(Product selectedProduct, int selectedProductQuantity) {
        if (selectedProduct == null) throw new IllegalArgumentException("Selected product cannot be null");
        if (selectedProductQuantity <= 0) throw new IllegalArgumentException("Selected product has to be positive");
        if (selectedProduct.getQuantity() < selectedProductQuantity) throw new IllegalArgumentException("Requested quantity more than available stock");
        int updatedQuantity = selectedProduct.getQuantity() - selectedProductQuantity;

        if (updatedQuantity == 0) inventory.remove(selectedProduct.getId());
        else {
            selectedProduct.setQuantity(updatedQuantity);
            inventory.put(selectedProduct.getId(), selectedProduct);
        }
    }

    public void shutdown() {
        scanner.close();
    }
}

public class Main {
    public static void main(String [] args) {
        List<Product> products = Arrays.asList(
                new BeverageProduct("p1", "Cola", 150, 10),
                new SnackProduct("p2", "Chips", 100, 5)
        );

        // Prepare scripted inputs:
        // Selection: productId=p1, quantity=2
        // Payment: method=UPI
        String scriptedInput = String.join("\n",
                "p1",  // product id
                "2",   // quantity
                "UPI"  // payment method
                // Home state after dispensing won't read more input in this scripted path
        );

        Scanner testScanner = new Scanner(
                new ByteArrayInputStream(scriptedInput.getBytes(StandardCharsets.UTF_8))
        );
        VendingMachineController controller = new VendingMachineController(products, testScanner);

        // Drive exactly 4 steps: Home -> Selection -> Payment -> Dispensing -> Home
        controller.run(4);

        // Verify that stock was decremented for p1 (expected: 8 left)
        Map<String, Product> inv = controller.getProducts();
        Product p1 = inv.get("p1");
        System.out.println("Post-purchase quantity of p1: " + (p1 != null ? p1.getQuantity() : 0));
    }
}