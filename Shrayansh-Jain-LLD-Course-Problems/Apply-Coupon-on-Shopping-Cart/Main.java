import java.util.*;

enum ProductType {
    ELECTRONICS, GROCERY
}

abstract class Product {
    private String id;
    private double price;
    private ProductType type;

    public Product() {

    }

    public Product(String id, double price, ProductType type) {
        this.id = id;
        this.price = price;
        this.type = type;
    }

    public String getId() {
        return id;
    }


    public ProductType getType() {
        return type;
    }

    public double getPrice() {
        return price;
    }
}

class ElectronicsProduct extends Product {
    public ElectronicsProduct(String id, double price) {
        super(id, price, ProductType.ELECTRONICS);
    }
}

class GroceryProduct extends Product {
    public GroceryProduct(String id, double price) {
        super(id, price, ProductType.GROCERY);
    }
}

class PercentageCouponDecorator extends Product {
    private final Product product;
    private final int discountPercentage;


    public PercentageCouponDecorator(Product product, int discountPercentage) {
        this.product = product;
        this.discountPercentage = discountPercentage;
    }

    @Override
    public double getPrice() {
        double price = product.getPrice();
        return price - (price * discountPercentage)/100;
    }

    @Override
    public ProductType getType() {
        return product.getType();
    }
}

class TypeCouponDecorator extends Product {
    private final Product product;
    private final int percentage;

    static List <ProductType> eligibleTypes = new ArrayList<>();

    static {
        eligibleTypes.add(ProductType.ELECTRONICS);
    }

    public TypeCouponDecorator(Product product, int percentage) {
        this.product = product;
        this.percentage = percentage;
    }

    @Override
    public double getPrice() {
        double price = product.getPrice();
        ProductType type = product.getType();
        System.out.println("type is: " + type);
        if (eligibleTypes.contains(type)) {
            System.out.println("Contains: true");
            return price - (price * percentage)/100; // if eligible, return the discounted price
        }
        return price; // if not eligible, return the original price
    }

    @Override
    public ProductType getType() {
        return product.getType();
    }
}

class ShoppingCart {
    List <Product> products;

    public ShoppingCart() {
        this.products = new ArrayList<>();
    }

    public void addToCart(Product product) {
        // firstly decorate the product with 10% discount, then apply an amount based discount
        Product productWithPercentageDiscount = new PercentageCouponDecorator(product, 10);
        Product productWithTypeDiscount = new TypeCouponDecorator(productWithPercentageDiscount, 10);

        products.add(productWithTypeDiscount);
    }

    public int getTotalPrice() {
        int totalPrice = 0;
        for (Product product: products) {
            totalPrice += product.getPrice();
        }
        return totalPrice;
    }
}

public class Main {
    public static void main(String [] args) {
//        Product item1 = new ElectronicsProduct("1", 100.0);
        Product item2 = new GroceryProduct("2", 200.0);

        ShoppingCart cart = new ShoppingCart();
//        cart.addToCart(item1);
        cart.addToCart(item2);

        System.out.println("Total price after discount: " + cart.getTotalPrice());
    }
}