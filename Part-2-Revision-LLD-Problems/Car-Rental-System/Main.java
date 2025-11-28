import java.util.*;
import java.time.*;
import java.util.stream.*;

enum VehicleType {
    CAR_SUV // Can be extended later based on requirements
}

enum BookingStatus {
    CONFIRMED, COMPLETED // Later 'CANCELLED' Status can also be added, depending on business requirements
}

class Booking {
    private final Vehicle vehicle;
    private BookingStatus status;
    private final Instant startTime;
    private final Instant endTime;
    private Instant releaseTime;

    public Booking(Vehicle vehicle, Instant startTime, Instant endTime) {
        if (vehicle == null || startTime == null || endTime == null) throw new IllegalArgumentException("Invalid parameters passed for booking");
        // UPDATE: Add validation to check that start time should be >= end time
        if (!startTime.isBefore(endTime)) throw new IllegalArgumentException("Start time must be before end time");
        this.vehicle = vehicle;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = BookingStatus.CONFIRMED;
    }

    public boolean release() {
        if (status != BookingStatus.CONFIRMED) {
            System.out.println("Cannot release a booking that is not yet in-progress");
            return false;
        }
        this.status = BookingStatus.COMPLETED;
        this.releaseTime = Instant.now();
        return true;
    }

    public boolean isUsedOverTime() {
        if (releaseTime == null) throw new IllegalStateException("Booking not yet released");
        return releaseTime.isAfter(endTime);
    }

    public Duration getOverTimeDuration() {
        if (releaseTime == null) throw new IllegalStateException("Booking not yet released");
        return Duration.between(endTime, releaseTime);
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public boolean isActive() {
        return this.status == BookingStatus.CONFIRMED;
    }

    public boolean isReleased() {
        return this.status == BookingStatus.COMPLETED;
    }

    public VehicleType getVehicleType() {
        return vehicle.getType();
    }

    public Duration getBookingDuration() {
        return Duration.between(startTime, endTime);
    }

    // UPDATE: Implemented toString method and print all the attributes
    @Override
    public String toString() {
        return "Booking{" + "vehicle ID =" + vehicle.getId() + ", vehicleType=" + vehicle.getType() + ", status=" + status + ", startTime=" + startTime + ", endTime=" + endTime +", releaseTime=" + releaseTime + ", duration="+ getBookingDuration().toHours() + "h" +'}';
    }
}

abstract class Vehicle {
    private final String id;
    private final VehicleType type;

    public Vehicle(String id, VehicleType vehicleType) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Vehicle ID cannot be null");
        if (vehicleType == null) throw new IllegalArgumentException("Vehicle type cannot be null");
        this.id = id;
        this.type = vehicleType;
    }

    public String getId() {
        return id;
    }

    public VehicleType getType() {
        return type;
    }

    // UPDATE: Implement toString method for Vehicle
    @Override
    public String toString() {
        return "Vehicle {" + "id=" + id + ", type= " + type + "}";
    }

    // UPDATE: CRITICAL -> Implement equals() and hashCode() methods, since we are using vehicle as a key in hashmap, it is critical
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Vehicle)) return false;
        Vehicle other = (Vehicle) obj;
        return this.id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

class SuvCar extends Vehicle {

    public SuvCar(String id) {
        super(id, VehicleType.CAR_SUV);
    }
}

class RentalStore {
    private final List <Vehicle> vehicles;
    private final Map <Vehicle, List<Booking>> vehicleBookings;

    public RentalStore() {
        this.vehicles = new ArrayList<>();
        this.vehicleBookings = new HashMap<>();
    }

    public void addVehicle(Vehicle vehicle) {
        Objects.requireNonNull(vehicle, "Vehicle to be added should be non-null");
        vehicles.add(vehicle);
        vehicleBookings.put(vehicle, new ArrayList<>());
    }

    public boolean isOverlappingActiveBooking(Booking booking, Instant startTime, Instant endTime) {
        if (booking == null || startTime == null || endTime == null) return false;
        if (!booking.isActive()) return false;
        Instant bookingStartTime = booking.getStartTime();
        Instant bookingEndTime = booking.getEndTime();
        // For simplicity, assuming that if startTime == bookingStartTime OR bookingStartTime == endTime, it will not be considered conflict as vehicle can be handed over
        return startTime.isBefore(bookingEndTime) && bookingStartTime.isBefore(endTime);
    }

    public List <Vehicle> getVehicles(VehicleType vehicleType, Instant startTime, Instant endTime) {
        if (vehicleType == null || startTime == null || endTime == null) throw new IllegalArgumentException("Invalid parameter(s) passed to getVehicles");
        if (!startTime.isBefore(endTime)) throw new IllegalArgumentException("Start time must be before end time");

        List <Vehicle> availableVehicles = new ArrayList<>();

        return vehicles.stream().filter(vehicle -> vehicle.getType() == vehicleType)
                                .filter(vehicle -> {
                                    List <Booking> bookings = vehicleBookings.get(vehicle);
                                    return bookings.stream().noneMatch(booking -> isOverlappingActiveBooking(booking, startTime, endTime));
                                }).collect(Collectors.toList());
    }

    // client side code is going to run in single threaded environment, so no need of concurrency control
    public Booking createBooking(Vehicle vehicle, Instant startTime, Instant endTime) {
        if (vehicle == null || startTime == null || endTime == null) throw new IllegalArgumentException("Invalid parameter(s) passed to createBooking");
        // UPDATE: Add validation to check that startTime >= endTime
        if (!startTime.isBefore(endTime)) throw new IllegalArgumentException("Start time must be before end time");
        // UPDATE: Add validation that vehicle exists in the store
        if (!vehicleBookings.containsKey(vehicle)) throw new IllegalArgumentException("Vehicle not found in rental store");

        boolean isVehicleUnavailable = vehicleBookings.get(vehicle).stream().anyMatch(booking -> isOverlappingActiveBooking(booking,startTime,endTime));
        if (isVehicleUnavailable) throw new IllegalArgumentException("Active booking for this vehicle already exists");
        Booking booking = new Booking(vehicle, startTime, endTime);
        vehicleBookings.get(vehicle).add(booking);
        return booking;
    }

    public boolean completeBooking(Booking booking, PaymentStrategy paymentStrategy) {
        if (booking == null || paymentStrategy == null) throw new IllegalArgumentException("Invalid parameters passed to completeBooking method");
        boolean releaseSuccess = booking.release();
        if (!releaseSuccess) return false;
        VehicleType vehicleType = booking.getVehicleType();
        long amount = RentCalculatorFactory.create(vehicleType).calculate(booking);
        return paymentStrategy.pay(amount);
    }

    // UPDATE: Add method to get all bookings for a vehicle (all historical+active bookings for a vehicle), this method will be used in the client side testing code
    public List <Booking> getBookingsForVehicle(Vehicle vehicle) {
        if (vehicle == null) throw new IllegalArgumentException("Vehicle cannot be null");
        List <Booking> bookings = vehicleBookings.get(vehicle);
        // Returning a copy of the original list
        return bookings!=null ? new ArrayList<>(bookings) : new ArrayList<>();
    }

    // UPDATE: Add a method to get all vehicles of rental store (Will be used by client side testing code)
    public List <Vehicle> getAllVehicles() {
        return new ArrayList<>(vehicles);
    }
}

class RentCalculatorFactory {
    public static RentCalculationStrategy create(VehicleType vehicleType) {
        switch (vehicleType) {
            case CAR_SUV: return new SUVCarRentCalculationStrategy();
            default: throw new IllegalArgumentException("Vehicle type not supported");
        }
    }
}

interface PaymentStrategy {
    public boolean pay(long amount);
}

class UPIPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean pay(long amount) {
        if (amount < 0) {
            System.out.println("Cannot process payment with negative amount");
            return false;
        }
        System.out.println("Paying amount: " + amount + " by UPI");
        return true;
    }
}

interface RentCalculationStrategy {
    public long calculate(Booking booking);

    default long getNumberOfHoursCeiling(Duration duration) {
        if (duration == null) throw new IllegalArgumentException("Duration cannot be null");
        return (long) Math.ceil(duration.getSeconds()/3600.0);
    }

    default long getNumberOfHoursFloor(Duration duration) {
        if (duration == null) throw new IllegalArgumentException("Duration cannot be null");
        return (long) Math.floor(duration.getSeconds()/3600.0);
    }
}

class SUVCarRentCalculationStrategy implements RentCalculationStrategy {
    private final long BASE_RENT = 100;
    private final long REGULAR_HOURLY_RENT = 200;
    private final long OVERUSE_HOURLY_RENT = 350;

    @Override
    public long calculate(Booking booking) {
        if (!booking.isReleased()) throw new IllegalArgumentException("Cannot calculate rent for booking which is not completed");
        if (booking.isUsedOverTime()) {
            Duration bookingDuration = booking.getBookingDuration();
            Duration overTimeDuration = booking.getOverTimeDuration();
            long bookedHours = getNumberOfHoursCeiling(bookingDuration);
            long overTimeHours = getNumberOfHoursFloor(overTimeDuration);
            return BASE_RENT + bookedHours * REGULAR_HOURLY_RENT + overTimeHours * OVERUSE_HOURLY_RENT;
        }
        else {
            Duration bookingDuration = booking.getBookingDuration();
            long bookedHours = getNumberOfHoursCeiling(bookingDuration);
            return BASE_RENT + REGULAR_HOURLY_RENT * bookedHours;
        }
    }
}

public class Main {
    public static void main(String [] args) {
        // UPDATED: Added comprehensive example usage
        System.out.println("=== Car Rental System Demo ===\n");

        // Create rental store
        RentalStore store = new RentalStore();

        // Add vehicles
        Vehicle suv1 = new SuvCar("SUV-001");
        Vehicle suv2 = new SuvCar("SUV-002");

        store.addVehicle(suv1);
        store.addVehicle(suv2);

        System.out.println("Added vehicles to store:");
        store.getAllVehicles().forEach(System.out::println);
        System.out.println();

        // Create booking
        Instant now = Instant.now();
        Instant startTime = now.plus(Duration.ofHours(1));
        Instant endTime = startTime.plus(Duration.ofHours(5));

        System.out.println("Checking available SUVs...");
        List<Vehicle> availableSUVs = store.getVehicles(VehicleType.CAR_SUV, startTime, endTime);
        System.out.println("Available SUVs: " + availableSUVs.size());

        if (!availableSUVs.isEmpty()) {
            Vehicle selectedVehicle = availableSUVs.get(0);
            System.out.println("Selected vehicle: " + selectedVehicle.getId());

            // Create booking
            Booking booking = store.createBooking(selectedVehicle, startTime, endTime);
            System.out.println("Booking created: " + booking);
            System.out.println();

            // Simulate time passing and complete booking
            System.out.println("Completing booking...");
            booking.release();

            // Calculate and pay
            PaymentStrategy paymentStrategy = new UPIPaymentStrategy();
            boolean paymentSuccess = store.completeBooking(booking, paymentStrategy);
            System.out.println("Payment successful: " + paymentSuccess);
            System.out.println();

            // Show booking history
            System.out.println("Booking history for " + selectedVehicle.getId() + ":");
            store.getBookingsForVehicle(selectedVehicle).forEach(System.out::println);
        }
    }
}