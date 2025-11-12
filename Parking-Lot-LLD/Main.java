import java.util.*;
import java.time.Instant;

enum VehicleType {
    BIKE(0), CAR(1), TRUCK(2);

    private final int size;

    VehicleType(int size) {
        this.size = size;
    }

    public int getSize() {
        return size;
    }
};

enum ParkingSlotStatus {
    OCCUPIED, VACANT
};

enum PaymentMethod {
    UPI, PAYPAL
};

record Vehicle(String id, VehicleType vehicleType) {


};


class ParkingSlot {
    private ParkingSlotStatus status;
    private final VehicleType vehicleType;

    public ParkingSlot(ParkingSlotStatus status, VehicleType vehicleType) {
        this.status = status;
        this.vehicleType = vehicleType;
    }

    public boolean isVacant() {
        return status == ParkingSlotStatus.VACANT;
    }

    public boolean canAccommodate(VehicleType requestedVehicleType) {
        return this.vehicleType.getSize() >= requestedVehicleType.getSize();
    }

    public void setStatus(ParkingSlotStatus status) {
        this.status = status;
    }

    // CHANGE: Expose tiny helpers like getters and setter
    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public void setOccupied() {
        this.status = ParkingSlotStatus.OCCUPIED;
    }

    public void setVacant() {
        this.status = ParkingSlotStatus.VACANT;
    }
};

class ParkingReceipt {
    private final VehicleType vehicleType;
    private final long entryTime;
    private final ParkingSlot parkingSlot;
    private long exitTime;

    public ParkingReceipt(VehicleType vehicleType, long entryTime, ParkingSlot parkingSlot) {
        this.vehicleType = vehicleType;
        this.entryTime = entryTime;
        this.parkingSlot = parkingSlot;
    }

    public void setExitTime(long exitTime) {
        this.exitTime = exitTime;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public long getEntryTime() {
        return entryTime;
    }

    public long getExitTime() {
        return exitTime;
    }

    public ParkingSlot getParkingSlot() {
        return parkingSlot;
    }
};

interface SlotSelectionStrategy {
    public Optional <ParkingSlot> select(List <ParkingSlot> slots, VehicleType required);
};

class BestFitSlotSelectionStrategy implements SlotSelectionStrategy {
    @Override
    public Optional <ParkingSlot> select(List <ParkingSlot> slots, VehicleType required) {
        return slots.stream()
                .filter(s -> s.isVacant() && s.canAccommodate(required))
                .min(Comparator.comparingInt(s -> s.getVehicleType().getSize()));
    }
};

class FirstFitSlotSelectionStrategy implements SlotSelectionStrategy {
    @Override
    public Optional <ParkingSlot> select(List <ParkingSlot> slots, VehicleType required) {
        return slots.stream()
                .filter(s -> s.isVacant() && s.canAccommodate(required))
                .findFirst();
    }
};

// CHANGE: ParkingLotManager: inject or default strategy; use it in getAvailableSlot
class ParkingLotManager {
    private List <ParkingSlot> parkingSlots = new ArrayList<>();
    private Map <String, ParkingReceipt> vehicleToParkingReceiptMap = new HashMap<>();
    private Scanner scanner = new Scanner(System.in);
    private final SlotSelectionStrategy slotSelection = new BestFitSlotSelectionStrategy();

    // Assumption : Execution of this code in single threaded environment only
    public void addParkingSlot(ParkingSlot parkingSlot) {
        parkingSlots.add(parkingSlot);
    }

    public void removeParkingSlot(ParkingSlot parkingSlot) {
        parkingSlots.remove(parkingSlot);
    }

    // CHANGE: Robust handling
    private VehicleType parseVehicleTypeInput(String input) {
        if (input == null) return null;
        String s = input.trim().toUpperCase();

        return switch(s) {
            case "BIKE" -> VehicleType.BIKE;
            case "CAR" -> VehicleType.CAR;
            case "TRUCK" -> VehicleType.TRUCK;
            default -> null;
        };
    }

    // CHANGE: use strategy for selection (best-fit)
    private Optional<ParkingSlot> getAvailableSlot(VehicleType vehicleType) {
        return slotSelection.select(parkingSlots, vehicleType);
    }

    private VehicleType inputVehicleType() {
        VehicleType vehicleType;
        while (true) {
            System.out.println("Hello, please enter your vehicle type: (BIKE/CAR/TRUCK)");
            String vehicleTypeInput = scanner.nextLine();
            vehicleType = parseVehicleTypeInput(vehicleTypeInput);
            if (vehicleType == null) {
                System.out.println("Invalid vehicle type passed, please try again");
                continue;
            }
            break;
        }
        return vehicleType;
    }

    // CHANGE: Robust parsing
    private PaymentMethod parsePaymentMethodInput(String input) {
        if (input == null) return null;
        String s = input.trim().toUpperCase();
        return switch(s) {
            case "PAYPAL" -> PaymentMethod.PAYPAL;
            case "UPI" -> PaymentMethod.UPI;
            default -> null;
        };
    }

    private PaymentMethod inputPaymentStrategy() {
        PaymentMethod paymentMethod;

        while (true) {
            System.out.println("Hello, please enter your payment method: (UPI/PAYPAL)");
            String paymentMethodInput = scanner.nextLine();
            paymentMethod = parsePaymentMethodInput(paymentMethodInput);
            if (paymentMethod == null) {
                System.out.println("Invalid payment method passed, please try again");
                continue;
            }
            break;
        }
        return paymentMethod;
    }

    private String inputVehicleId() {
        System.out.println("Please enter your vehicle Id:");
        String vehicleId = scanner.nextLine();
        return vehicleId;
    }

    private long getCurrentEpochSeconds() {
        return Instant.now().getEpochSecond();
    }

    // CHANGE: use helpers on reserve/release
    private void reserveParkingSlot(Vehicle vehicle, ParkingSlot parkingSlot) {
        parkingSlot.setOccupied();
        ParkingReceipt parkingReceipt = new ParkingReceipt(vehicle.vehicleType(), getCurrentEpochSeconds(), parkingSlot);
        vehicleToParkingReceiptMap.put(vehicle.id(), parkingReceipt);
    }

    public void enter() {
        VehicleType vehicleType = inputVehicleType();
        Optional <ParkingSlot> parkingSlot = getAvailableSlot(vehicleType);

        // CHANGE: Use Optional.isEmpty() for readability.
        if (parkingSlot.isEmpty()) {
            System.out.println("Sorry, no available slot at this moment.");
            return ;
        }

        String vehicleId = inputVehicleId();
        // CHANGE: enforce unique vehicle id at entry
        if (vehicleToParkingReceiptMap.containsKey(vehicleId)) {
            System.out.println("Vehicle ID already present, please check your ID.");
            return;
        }

        Vehicle vehicle = new Vehicle(vehicleId, vehicleType);
        reserveParkingSlot(vehicle, parkingSlot.get());
    }

    public void exit() {
        String vehicleId = inputVehicleId();
        if (!vehicleToParkingReceiptMap.containsKey(vehicleId)) {
            System.out.println("No receipt found for this vehicle ID");
            return ;
        }
        ParkingReceipt parkingReceipt = vehicleToParkingReceiptMap.get(vehicleId);
        parkingReceipt.setExitTime(getCurrentEpochSeconds());

        FareCalculationStrategy fareCalculationStrategy = FareCalculationFactory.create(parkingReceipt.getVehicleType());
        int amount = fareCalculationStrategy.calculate(parkingReceipt);

        PaymentMethod paymentMethod = inputPaymentStrategy();
        PaymentStrategy paymentStrategy = PaymentStrategyFactory.create(paymentMethod);

        boolean paymentStatus = paymentStrategy.pay(amount);
        if (paymentStatus) {
            System.out.println("Payment successfully processed");
            vehicleToParkingReceiptMap.remove(vehicleId);
            parkingReceipt.getParkingSlot().setVacant();
        }
        else {
            System.out.println("Payment failed");
        }
    }

};

interface FareCalculationStrategy {
    final double NUM_SECONDS_IN_HOUR = 3600;

    // CHANGE: Defensive coding, for negative delta, we return 0 as the number of billing hours
    static int calculateNumberOfHours(long entryTime, long exitTime) {
        // ceil of the difference
        long delta = Math.max(0, exitTime - entryTime);
        if (delta == 0) return 0;
        return (int) Math.ceil(delta/NUM_SECONDS_IN_HOUR);
    }

    public int calculate(ParkingReceipt parkingReceipt);
};

class BikeFareCalculationStrategy implements FareCalculationStrategy {
    private final int BASE_FEES = 40;
    private final int HOURLY_FEES = 50;

    @Override
    public int calculate(ParkingReceipt parkingReceipt) {
        int numHours = FareCalculationStrategy.calculateNumberOfHours(parkingReceipt.getEntryTime(), parkingReceipt.getExitTime());
        return BASE_FEES + HOURLY_FEES * numHours;
    }
};

class CarFareCalculationStrategy implements FareCalculationStrategy {
    private final int BASE_FEES = 100;
    private final int HOURLY_FEES = 60;

    @Override
    public int calculate(ParkingReceipt parkingReceipt) {
        int numHours = FareCalculationStrategy.calculateNumberOfHours(parkingReceipt.getEntryTime(), parkingReceipt.getExitTime());
        return BASE_FEES + HOURLY_FEES * numHours;
    }
};

class TruckFareCalculationStrategy implements FareCalculationStrategy {
    private final int BASE_FEES = 150;
    private final int HOURLY_FEES = 80;

    @Override
    public int calculate(ParkingReceipt parkingReceipt) {
        int numHours = FareCalculationStrategy.calculateNumberOfHours(parkingReceipt.getEntryTime(), parkingReceipt.getExitTime());
        return BASE_FEES + HOURLY_FEES * numHours;
    }
};

class FareCalculationFactory {
    public static FareCalculationStrategy create(VehicleType vehicleType) {
        switch (vehicleType) {
            case BIKE: return new BikeFareCalculationStrategy();
            case CAR: return new CarFareCalculationStrategy();
            case TRUCK: return new TruckFareCalculationStrategy();
            default: throw new IllegalArgumentException("Vehicle type not supported");
        }
    }
};

interface PaymentStrategy {
    public boolean pay(int amount);
};

class UPIPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean pay(int amount) {
        return true;
    }
};


class PaypalPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean pay(int amount) {
        return false;
    }
};

class PaymentStrategyFactory {
    public static PaymentStrategy create(PaymentMethod paymentMethod) {
        switch (paymentMethod) {
            case UPI:
                return new UPIPaymentStrategy();
            case PAYPAL:
                return new PaypalPaymentStrategy();
            default:
                throw new IllegalArgumentException("Payment method not supported");
        }
    }
};

public class Main {
    public static void main(String[] args) throws Exception {
        String inputs =
                "CAR\nCAR-123\n" +
                        "TRUCK\nTRUCK-1\n" +
                        "BIKE\nBIKE-88\n" +
                        "CAR\n" +
                        "BIKE-88\nUPI\n" +
                        "TRUCK-1\nPAYPAL\n" +
                        "TRUCK-1\nUPI\n" +
                        "CAR-123\nUPI\n" +
                        "UNKNOWN\nUPI\n";

        // Inject scripted input BEFORE constructing the manager (so its Scanner uses this stream)
        System.setIn(new java.io.ByteArrayInputStream(inputs.getBytes()));

        ParkingLotManager mgr = new ParkingLotManager();

        // Pre-provision parking slots (adjust as needed)
        mgr.addParkingSlot(new ParkingSlot(ParkingSlotStatus.VACANT, VehicleType.CAR));
        mgr.addParkingSlot(new ParkingSlot(ParkingSlotStatus.VACANT, VehicleType.TRUCK));
        mgr.addParkingSlot(new ParkingSlot(ParkingSlotStatus.VACANT, VehicleType.BIKE));

        // Entries
        mgr.enter();   // CAR-123
        mgr.enter();   // TRUCK-1
        mgr.enter();   // BIKE-88
        mgr.enter();   // CAR (no slot available)

        // Ensure exitTime > entryTime for fee calc
        Thread.sleep(1000);

        // Exits
        mgr.exit();    // BIKE-88, UPI -> success
        mgr.exit();    // TRUCK-1, PAYPAL -> fail
        mgr.exit();    // TRUCK-1, UPI -> success
        mgr.exit();    // CAR-123, UPI -> success
        mgr.exit();    // UNKNOWN -> no receipt
    }
}