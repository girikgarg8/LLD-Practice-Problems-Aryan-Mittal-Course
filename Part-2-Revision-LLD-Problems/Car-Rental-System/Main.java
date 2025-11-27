import java.util.*;
import java.time.*;

enum VehicleType {
    CAR_SUV // Can be extended later based on requirements
}

enum BookingStatus {
    CONFIRMED, COMPLETED
}

class Booking {
    private final Vehicle vehicle;
    private BookingStatus status;
    private final Instant startTime;
    private final Instant endTime;
    private Instant releaseTime;

    public Booking(Vehicle vehicle, Instant startTime, Instant endTime) {
        if (vehicle == null || startTime == null || endTime == null) throw new IllegalArgumentException("Invalid parameters passed for booking");
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

    // TODO: Override toString method and print the attributes

    // Write getters as necessary
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

    public VehicleType getType() {
        return type;
    }

    // Write getters as necessary
}

class SuvCar extends Vehicle {

    public SuvCar(String id, long baseFare, long hourlyFare) {
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
        if (booking == null) return false;
        if (!booking.isActive()) return false;
        Instant bookingStartTime = booking.getStartTime();
        Instant bookingEndTime = booking.getEndTime();
        // For simplicity, assuming that if startTime == bookingStartTime OR bookingStartTime == endTime, it will not be considered conflict as vehicle can be handed over
        return startTime.isBefore(bookingEndTime) && bookingStartTime.isBefore(endTime);
    }

    public List <Vehicle> getVehicles(VehicleType vehicleType, Instant startTime, Instant endTime) {
        if (vehicleType == null || startTime == null || endTime == null) throw new IllegalArgumentException("Invalid parameter(s) passed to getVehicles");
        List <Vehicle> availableVehicles = new ArrayList<>();
        // TODO: Convert to stream
        for (Vehicle vehicle: vehicles) {
            if (vehicle.getType() == vehicleType) {
                List <Booking> bookings = vehicleBookings.get(vehicle);
                boolean isVehicleAvailable = bookings.stream().noneMatch(booking -> isOverlappingActiveBooking(booking,startTime,endTime));
                if (isVehicleAvailable) availableVehicles.add(vehicle);
            }
        }
        return availableVehicles;
    }

    // client side code is going to run in single threaded environment, so no need of concurrency control
    public Booking createBooking(Vehicle vehicle, Instant startTime, Instant endTime) {
        if (vehicle == null || startTime == null || endTime == null) throw new IllegalArgumentException("Invalid parameter(s) passed to createBooking");
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



    }
}