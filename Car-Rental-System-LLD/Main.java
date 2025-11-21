import java.util.*;
import java.util.stream.*;
import java.time.*;

enum VehicleType {
    TWO_WHEELER, THREE_WHEELER, FOUR_WHEELER
};

abstract class Vehicle {
    private final String id;
    private final String model;
    private final VehicleType type;

    public Vehicle(String id, String model, VehicleType type) {
        if (id == null || model == null || type == null) throw new IllegalArgumentException("Invalid parameter(s) passed for vehicle");
        this.id = id;
        this.model = model;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public VehicleType getType() {
        return type;
    }

    public String getModel() {
        return model;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Vehicle)) return false;
        Vehicle other = (Vehicle) obj;
        return other.id.equals(this.id);
    }

    @Override
    public String toString() {
        return "Vehicle: id= " + id + " model= " + model + " type= " + type;
    }
};

class Car extends Vehicle {
    public Car(String id, String model) {
        super(id, model, VehicleType.FOUR_WHEELER);
    }
};

class Bike extends Vehicle {
    public Bike(String id, String model) {
        super(id, model, VehicleType.TWO_WHEELER);
    }
};

class RentalStore {
    private final Map <String, Vehicle> vehiclesMap;
    private final List <Booking> bookings;

    public RentalStore(List <Vehicle> vehicles) {
        this.vehiclesMap = new HashMap<>();
        vehicles.forEach(vehicle -> vehiclesMap.put(vehicle.getId(), vehicle));
        this.bookings = new ArrayList<>();
    }

    // CHANGE: Add check to only return the available vehicle (which don't have any booking)
    public List <Vehicle> getAvailableVehiclesByType(VehicleType vehicleType) {
        if (vehicleType == null) throw new IllegalArgumentException("vehicle type cannot be null");
        return vehiclesMap.values().stream().filter(vehicle -> vehicle.getType() == vehicleType).filter(vehicle -> getActiveBookingForVehicle(vehicle.getId()).isEmpty()).collect(Collectors.toList());
    }

    public Optional<Booking> getActiveBookingForVehicle(String vehicleId) {
        return bookings.stream().filter(booking -> booking.getVehicle().getId().equals(vehicleId) && booking.isActive()).findFirst();
    }

    // CHANGE: Return Optional <Vehicle> in the method called 'getVehicleById' for better semantics
    public Optional <Vehicle> getVehicleById(String vehicleId) {
        return Optional.ofNullable(vehiclesMap.get(vehicleId));
    }

    // Client side code is going to run in single threaded environment, so no requirement of concurrency control
    // CHANGE: Return booking object for better tracking
    public Booking addBooking(Vehicle vehicle) {
        if (vehicle == null) throw new IllegalArgumentException("Vehicle cannot be null");
        String vehicleId = vehicle.getId();
        if (getVehicleById(vehicleId).isEmpty()) throw new IllegalArgumentException("Vehicle not found in the rental store");
        if (getActiveBookingForVehicle(vehicleId).isPresent()) throw new IllegalArgumentException("Already active booking for vehicle");
        Booking booking = new Booking(UUID.randomUUID().toString(), vehicle);
        bookings.add(booking);
        return booking;
    }

    public void cancelBooking(Vehicle vehicle) {
        if (vehicle == null) throw new IllegalArgumentException("Vehicle cannot be null");
        String vehicleId = vehicle.getId();
        Optional <Booking> booking = getActiveBookingForVehicle(vehicleId);
        if (booking.isEmpty()) throw new IllegalArgumentException("No active booking for this vehicle");
        Booking activeBooking = booking.get();
        activeBooking.cancel();
    }

    // CHANGE: Add method to change booking status to 'COMPLETE' only after payment is successful
    public void completeBooking(Booking booking) {
        if (booking == null) throw new IllegalArgumentException("Booking cannot be null");
        booking.complete();
    }
};

enum BookingStatus {
    ACTIVE, CANCELLED, COMPLETED
};

class Booking {
    private final String id;
    private final Vehicle vehicle;
    private final Instant createdAt;
    private Instant releasedAt;
    private BookingStatus status;

    public Booking(String id, Vehicle vehicle) {
        if (id == null || vehicle == null) throw new IllegalArgumentException("Invalid parameters passed for booking");
        this.id = id;
        this.vehicle = vehicle;
        this.createdAt = Instant.now();
        this.status = BookingStatus.ACTIVE;
    }

    // CHANGE: Add getter for ID, important for observability
    public String getId() {
        return id;
    }

    // CHANGE: Add getter for status - important for observability
    public BookingStatus getStatus() {
        return status;
    }

    public void cancel() {
        // CHANGE: Added validation to prevent state corruption
        if (this.status != BookingStatus.ACTIVE) {
            throw new IllegalArgumentException("Cannot cancel booking in: " + status + " status");
        }
        this.status = BookingStatus.CANCELLED;
        this.releasedAt = Instant.now();
    }

    public void release() {
        // CHANGE: Release just sets the end time, keeps the status active
        if (this.status != BookingStatus.ACTIVE) {
            throw new IllegalArgumentException("Cannot release booking in: " + status + " status");
        }
        this.releasedAt = Instant.now();
     }

    // CHANGE: complete() method changes the status to 'COMPLETED'. Intended to be used once the payment is sucessful
    public void complete() {
        if (this.status != BookingStatus.ACTIVE) throw new IllegalArgumentException("Cannot complete booking in: "+ status + " status");
        if (this.releasedAt == null) throw new IllegalArgumentException("Cannot complete a booking which is not yet released");
        this.status = BookingStatus.COMPLETED;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public boolean isActive() {
        return status == BookingStatus.ACTIVE;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    // UPDATE: Add toString for further debugging
    public String toString() {
        return "Booking {ID = " + id + ", vehicle = " + vehicle.getId() + " createdAt= "+ createdAt + " status = " + status + " }";
    }
};

interface PaymentStrategy {
    public boolean pay(long amount);
};

class UPIPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean pay(long amount) {
        // CHANGE: Add validation for negative amount
        if (amount < 0) throw new IllegalArgumentException("Amount cannot be negative");
        System.out.println("Paying amount: " + amount + " with UPI");
        return true;
    }
};

class CreditCardPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean pay(long amount) {
        // CHANGE: Add validation for negative amount
        if (amount < 0) throw new IllegalArgumentException("Amount cannot be negative");
        System.out.println("Paying amount: " + amount + " with credit card");
        return true;
    }
};

interface FareCalculationStrategy {
    public long calculate(Booking booking);

    default long calculateHours(Booking booking) {
        Instant startTime = booking.getCreatedAt();
        Instant endTime = booking.getReleasedAt();

        // CHANGE: Add null check for releasedAt
        if (endTime == null) throw new IllegalStateException("Cannot calculate fare for booking that hasn't been released");

        long durationSeconds = Duration.between(startTime, endTime).toSeconds();
        return (long) Math.ceil((double) durationSeconds / (60 * 60.0));
    }
};

class TwoWheelerFareCalculationStrategy implements FareCalculationStrategy {
    private final static int BASE_FARE = 50;
    private final static int HOURLY_RATE = 100;

    @Override
    public long calculate(Booking booking) {
        return BASE_FARE + HOURLY_RATE * calculateHours(booking);
    }
};

class ThreeWheelerFareCalculationStrategy implements FareCalculationStrategy {
    private static final int BASE_FARE = 70;
    private static final int HOURLY_RATE = 150;

    @Override
    public long calculate(Booking booking) {
        return BASE_FARE + HOURLY_RATE * calculateHours(booking);
    }
};

class FourWheelerFareCalculationStrategy implements FareCalculationStrategy {
    private static final int BASE_FARE = 90;
    private static final int HOURLY_RATE = 200;

    @Override
    public long calculate(Booking booking) {
        return BASE_FARE + HOURLY_RATE * calculateHours(booking);
    }
};

class FareCalculationStrategyFactory {
    public static FareCalculationStrategy create(VehicleType vehicleType) {
        switch (vehicleType) {
            case TWO_WHEELER : return new TwoWheelerFareCalculationStrategy();
            case THREE_WHEELER: return new ThreeWheelerFareCalculationStrategy();
            case FOUR_WHEELER: return new FourWheelerFareCalculationStrategy();
            default: throw new IllegalArgumentException("Vehicle type not supported");
        }
    }
};

// CHANGE: Create Payment Service to decouple payment logic from Rental Store Controller
class PaymentService {
    public boolean processInteractivePayment(long amount, Scanner scanner, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            System.out.println("\n Attempt " + attempt + " of " + maxAttempts);
            System.out.println("Total amount: " + amount);
            System.out.println("Select payment method (UPI/CREDIT_CARD)");

            String input = scanner.nextLine().trim().toUpperCase();
            PaymentStrategy paymentStrategy = switch(input) {
                case "UPI" -> new UPIPaymentStrategy();
                case "CREDIT_CARD" -> new CreditCardPaymentStrategy();
                default -> null;
            };

            if (paymentStrategy == null) {
                System.out.println("Invalid payment mode selected");
                if (attempt < maxAttempts) {
                    System.out.println("Please try again");
                    continue;
                }
            }

            try {
                if (paymentStrategy.pay(amount)) {
                    return true;
                }
                System.out.println("Payment failed.");
            }
            catch (Exception ex) {
                System.out.println("Payment failure: " + ex.getMessage());
            }

            if (attempt < maxAttempts) {
                System.out.println("Would you like to try again?");
            }
        }
        System.out.println("Payment failed after "+ maxAttempts + " attempts");
        return false;
    }
};

class RentalStoreController {
    private final Scanner scanner;
    private final PaymentService paymentService;
    private final static int MAX_ATTEMPTS = 3;

    public RentalStoreController() {
        scanner = new Scanner(System.in);
        this.paymentService = new PaymentService();
    }

    public List <Vehicle> getAvailableVehicles(RentalStore rentalStore, VehicleType vehicleType) {
        if (rentalStore == null || vehicleType == null) throw new IllegalArgumentException("Store and type need to be non-null");
        return rentalStore.getAvailableVehiclesByType(vehicleType);
    }

    public Booking createBooking(RentalStore rentalStore, Vehicle vehicle) {
        try {
            Booking booking = rentalStore.addBooking(vehicle);
            System.out.println("Booking created successfully");
            return booking;
        }
        catch (Exception ex) {
            System.out.println("Could not create booking because of exception: " +ex);
            return null;
        }
    }

    public void cancelBooking(RentalStore rentalStore, Vehicle vehicle) {
        try {
            rentalStore.cancelBooking(vehicle);
        }
        catch (Exception ex) {
            System.out.println("Could not cancel booking because of exception: " + ex);
        }
    }

    public void releaseBooking(RentalStore rentalStore, Vehicle vehicle) {
        Optional <Booking> booking = rentalStore.getActiveBookingForVehicle(vehicle.getId());
        if (booking.isEmpty()) {
            System.out.println("Cannot release booking because no active booking for this vehicle");
            return ;
        }
        Booking activeBooking = booking.get();
        activeBooking.release();
        long amount = FareCalculationStrategyFactory.create(vehicle.getType()).calculate(activeBooking);

        boolean paymentSuccessful = paymentService.processInteractivePayment(amount, scanner, MAX_ATTEMPTS);

        if (paymentSuccessful) {
            activeBooking.complete();
            System.out.println("Booking completed successfully");
        }
        else {
            System.out.println("Booking remains active, please contact support to retry payment");
        }
    }

    public void close() {
        scanner.close();
    }
};

public class Main {
    public static void main(String[] args) {
        // UPDATED: Added example usage demonstrating the system
        List<Vehicle> vehicles = Arrays.asList(
                new Bike("B001", "Honda Activa"),
                new Car("C001", "Toyota Camry"),
                new Bike("B002", "Royal Enfield")
        );

        RentalStore store = new RentalStore(vehicles);
        RentalStoreController controller = new RentalStoreController();

        System.out.println("=== Vehicle Rental System ===");
        System.out.println("Available Two Wheelers: " +
                controller.getAvailableVehicles(store, VehicleType.TWO_WHEELER).size());

        // Example: Create a booking
        Vehicle bike = store.getVehicleById("B001").get();
        Booking booking = controller.createBooking(store, bike);

        System.out.println("Active booking created: " + booking);

        controller.close();
    }
}