import java.util.*;
import java.time.*;

enum VehicleType {
    BIKE(0), CAR(1), TRUCK(2);

    private int size;

    VehicleType(int size) {
        this.size = size;
    }

    public int getSize() {
        return size;
    }
}

enum SlotStatus {
    VACANT, OCCUPIED
}

abstract class Vehicle {
    private final String id;
    private final VehicleType type;

    public Vehicle(String id, VehicleType type) {
        if (id == null || type == null) throw new IllegalArgumentException("Invalid parameters passed for vehicle");
        this.id = id;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public VehicleType getType() {
        return type;
    }
}

class ParkingLot {
    private final String id;
    private final VehicleType type;
    private SlotStatus status;

    public ParkingLot(String id, VehicleType type) {
        if (id == null || type == null) throw new IllegalArgumentException("Invalid parameters passed for parking lot");
        this.id = id;
        this.type = type;
        this.status = SlotStatus.VACANT;
    }

    public String getId() {
        return id;
    }

    public VehicleType getType() {
        return type;
    }

    public boolean isOccupied() {
        return status == SlotStatus.OCCUPIED;
    }

    public boolean canAccommodate(VehicleType other) {
        return getSize() >= other.getSize();
    }

    public int getSize() {
        return this.type.getSize();
    }

    public void setVacant() {
        this.status = SlotStatus.VACANT;
    }

    //CHANGED: Missed updating the parking slot to 'occupied' when created a booking :)
    public void setOccupied() {
        this.status = SlotStatus.OCCUPIED;
    }
}

class ParkingLotManagerResponse {
    private final boolean success;
    private final String message;

    public ParkingLotManagerResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}

enum BookingStatus {
    CREATED, RELEASED, COMPLETED
}

class Booking {
    private final String id;
    private final Vehicle vehicle;
    private final ParkingLot parkingLot;
    private final Instant createdAt;
    private Instant releasedAt;
    private BookingStatus status;

    public Booking(String id, Vehicle vehicle, ParkingLot parkingLot) {
        this.id = id;
        this.vehicle = vehicle;
        this.parkingLot = parkingLot;
        this.createdAt = Instant.now();
        this.status = BookingStatus.CREATED;
    }

    public String getId() {
        return id;
    }

    public void release() {
        // Only transition from CREATED, don't degrade COMPLETED
        if (status == BookingStatus.CREATED) {
            releasedAt = Instant.now();
            status = BookingStatus.RELEASED;
        }
    }

    public void complete() {
        status = BookingStatus.COMPLETED;
    }

    public VehicleType getVehicleType() {
        return vehicle.getType();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public ParkingLot getParkingLot() {
        return parkingLot;
    }
}

class ParkingLotManager {
    private final List <ParkingLot> parkingLots;
    private final Map <String, Booking> bookings;
    private final AllotmentStrategy allotmentStrategy;
    // UPDATE: Constsnt should be static final
    private final static int MAX_PAYMENT_ATTEMPTS = 3;

    public ParkingLotManager(List <ParkingLot> parkingLots, AllotmentStrategy allotmentStrategy) {
        if (parkingLots == null || allotmentStrategy == null) throw new IllegalArgumentException("Invalid parameters passed to parking lot manager");
        this.parkingLots = new ArrayList<>(parkingLots);
        this.bookings = new HashMap<>();
        this.allotmentStrategy = allotmentStrategy;
    }

    public boolean checkSlotAvailability(VehicleType type) {
        return parkingLots.stream().anyMatch(parkingLot -> parkingLot.canAccommodate(type) && !parkingLot.isOccupied());
    }

    // This code will execute in single threaded environment, no need to have concurrency handling
    public Booking bookSlot(Vehicle vehicle) {
        // UPDATE: Reove usage of optional.get() with the help of orElseThrow
        ParkingLot allocatedParkingLot = allotmentStrategy.getParkingLot(Collections.unmodifiableList(parkingLots), vehicle.getType()).orElseThrow(() -> new IllegalArgumentException("No available slot for given type"));
        String bookingId = UUID.randomUUID().toString();
        Booking booking = new Booking(bookingId, vehicle, allocatedParkingLot);
        // UPDATE: Mark the slot as occupied to prevent double booking
        allocatedParkingLot.setOccupied();
        bookings.put(bookingId, booking);
        return booking;
    }

    public ParkingLotManagerResponse completeBooking(String bookingId, PaymentStrategy paymentStrategy) {
        if (!bookings.containsKey(bookingId)) return new ParkingLotManagerResponse(false, "No booking found with given ID");
        Booking booking = bookings.get(bookingId);
        booking.release();

        VehicleType vehicleType = booking.getVehicleType();
        ParkingLot parkingLot = booking.getParkingLot();
        // Comment: Slot is freed upon release, business rule: we free capacity regardless of payment outcome

        parkingLot.setVacant();

        long amount = FareCalculationStrategyFactory.create(vehicleType).calculate(booking);

        if (PaymentService.attemptPayment(paymentStrategy, amount, MAX_PAYMENT_ATTEMPTS)) {
            booking.complete();
            return new ParkingLotManagerResponse(true, "Booking successfully completed");
        }

        return new ParkingLotManagerResponse(false, "Booking couldn't be completed due to payment issue, please contact support");
    }
}

class PaymentService {
    public static boolean attemptPayment(PaymentStrategy paymentStrategy, long amount, int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            if (paymentStrategy.pay(amount)) return true;
        }
        return false;
    }
}

interface FareCalculationStrategy {
    public long calculate(Booking booking);

    default long getNumberOfHours(Booking booking) {
        Instant startTime = booking.getCreatedAt();
        Instant endTime = booking.getReleasedAt();
        if (endTime == null) throw new IllegalArgumentException("Cannot calculate fare for booking which is not yet released");
        Duration duration = Duration.between(startTime, endTime);
        double durationInHours = duration.toSeconds()/(3600.0);
        return (long) Math.ceil(durationInHours);
    }
}

class BikeFareCalculationStrategy implements FareCalculationStrategy {
    private final static int BASE_FARE = 30;
    private final static int HOURLY_RATE = 100;

    @Override
    public long calculate(Booking booking) {
        return BASE_FARE + HOURLY_RATE * getNumberOfHours(booking);
    }
}

class CarFareCalculationStrategy implements FareCalculationStrategy {
    private final static int BASE_FARE = 50;
    private final static int HOURLY_RATE = 150;

    @Override
    public long calculate(Booking booking) {
        return BASE_FARE + HOURLY_RATE * getNumberOfHours(booking);
    }
}

class TruckFareCalculationStrategy implements FareCalculationStrategy {
    private final static int BASE_FARE = 100;
    private final static int HOURLY_RATE = 200;

    @Override
    public long calculate(Booking booking) {
        return BASE_FARE + HOURLY_RATE * getNumberOfHours(booking);
    }
}

class FareCalculationStrategyFactory {
    public static FareCalculationStrategy create(VehicleType vehicleType) {
        switch (vehicleType) {
            case BIKE : return new BikeFareCalculationStrategy();
            case CAR : return new CarFareCalculationStrategy();
            case TRUCK : return new TruckFareCalculationStrategy();
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
        if (amount < 0) throw new IllegalArgumentException("Cannot pay negative amount");
        return true;
    }
}

class CreditCardPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean pay(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Cannot pay negative amount");
        return false;
    }
}

enum PaymentMethod {
    UPI, CREDIT_CARD
}

interface AllotmentStrategy {
    public Optional <ParkingLot> getParkingLot(List <ParkingLot> parkingLots, VehicleType type);
}

class BestFitAllotmentStrategy implements AllotmentStrategy {
    @Override
    public Optional <ParkingLot> getParkingLot(List <ParkingLot> parkingLots, VehicleType type) {
        return parkingLots.stream().filter(parkingLot -> parkingLot.canAccommodate(type) && !parkingLot.isOccupied())
                .min(Comparator.comparingInt(ParkingLot::getSize));
    }
}

class FirstFitAllotmentStrategy implements AllotmentStrategy {
    @Override
    public Optional <ParkingLot> getParkingLot(List <ParkingLot> parkingLots, VehicleType type) {
        return parkingLots.stream().filter(parkingLot -> parkingLot.canAccommodate(type) && !parkingLot.isOccupied())
                .findFirst();
    }
}

public class Main {
    public static void main(String [] args) {
        // Create slots
        List<ParkingLot> lots = new ArrayList<>();
        lots.add(new ParkingLot("S1", VehicleType.BIKE));
        lots.add(new ParkingLot("S2", VehicleType.CAR));
        lots.add(new ParkingLot("S3", VehicleType.TRUCK)); // can take any

        // Manager with best-fit strategy
        AllotmentStrategy strategy = new BestFitAllotmentStrategy();
        ParkingLotManager manager = new ParkingLotManager(lots, strategy);

        // Anonymous concrete vehicles
        Vehicle bike = new Vehicle("V-B1", VehicleType.BIKE) {};
        Vehicle car  = new Vehicle("V-C1", VehicleType.CAR) {};

        // Availability
        System.out.println("Bike slot available: " + manager.checkSlotAvailability(VehicleType.BIKE));
        System.out.println("Car slot available: " + manager.checkSlotAvailability(VehicleType.CAR));

        // Book a car slot
        Booking carBooking = manager.bookSlot(car);
        System.out.println("Booked car: bookingId=" + carBooking.getId() +
                ", slotId=" + carBooking.getParkingLot().getId());

        // Complete with UPI (success)
        ParkingLotManagerResponse resp1 =
                manager.completeBooking(carBooking.getId(), new UPIPaymentStrategy());
        System.out.println("Complete car booking: success=" + resp1.isSuccess() +
                ", msg=" + resp1.getMessage());

        // Book a bike slot
        Booking bikeBooking = manager.bookSlot(bike);
        System.out.println("Booked bike: bookingId=" + bikeBooking.getId() +
                ", slotId=" + bikeBooking.getParkingLot().getId());

        // Complete with credit card (forced failure per stub)
        ParkingLotManagerResponse resp2 =
                manager.completeBooking(bikeBooking.getId(), new CreditCardPaymentStrategy());
        System.out.println("Complete bike booking: success=" + resp2.isSuccess() +
                ", msg=" + resp2.getMessage());

        // Re-run completion on an already completed booking to verify no status downgrade/regression
        ParkingLotManagerResponse resp3 =
                manager.completeBooking(carBooking.getId(), new UPIPaymentStrategy());
        System.out.println("Repeat complete car booking: success=" + resp3.isSuccess() +
                ", msg=" + resp3.getMessage());

    }
}