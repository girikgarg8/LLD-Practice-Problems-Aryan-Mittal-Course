import java.util.*;

enum ElevatorStatus {
    IDLE, MOVING_UP, MOVING_DOWN, MAINTENANCE
}

interface ElevatorState {
    public ElevatorStatus getStatus();
    default boolean isOperational() {
        return true;
    }
}

class IdleState implements ElevatorState {

    @Override
    public ElevatorStatus getStatus() {
        return ElevatorStatus.IDLE;
    }
}

class MovingUpState implements ElevatorState {

    @Override
    public ElevatorStatus getStatus() {
        return ElevatorStatus.MOVING_UP;
    }
}

class MovingDownState implements ElevatorState {

    @Override
    public ElevatorStatus getStatus() {
        return ElevatorStatus.MOVING_DOWN;
    }
}

class MaintenanceState implements ElevatorState {

    @Override
    public ElevatorStatus getStatus() {
        return ElevatorStatus.MAINTENANCE;
    }

    @Override
    public boolean isOperational() {
        return false;
    }
}

class Elevator {
    private final String id;
    private final int minFloor;
    private final int maxFloor;
    private ElevatorState currentState;
    private int currentFloor;
    private final SchedulingStrategy schedulingStrategy;
    // UPDATE: Add a flag for graceful shutdown, client can set this flag to false when they no want to terminate the elevator
    // Not making it volatile since assumption is main thread itself will modify it
    private boolean running = true;

    public Elevator(String id, int minFloor, int maxFloor, int initialFloor, SchedulingStrategy schedulingStrategy) {
        if (id == null || schedulingStrategy == null) throw new IllegalArgumentException("Invalid parameter for elevator");
        if (minFloor > maxFloor) throw new IllegalArgumentException("Min floor cannot be greater than max floor");
        if (initialFloor < minFloor || initialFloor > maxFloor) throw new IllegalArgumentException("Initial floor has to be in range between min and max floors");
        this.id = id;
        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
        this.currentFloor = initialFloor;
        this.schedulingStrategy = schedulingStrategy;
        this.currentState = new IdleState();
    }

    // UPDATE: Fixed movement logic - delta was inverted
    private void move(int source, int destination) {
        Integer delta = Integer.compare(destination, source); // UPDATE: Fixed order
        int iterator = source;
        while (iterator != destination) {
            iterator+=delta;
            this.currentFloor = iterator;

            // UPDATE: Added simulation delay for realistic behaviour
            try {
                Thread.sleep(1000); // 1 second per floor
                System.out.println(String.format("[%s] Now at floor %d", id, currentFloor));
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void execute() {
        while (running) { // UPDATED: Added graceful exit condition, instead of using infinite while loop
            if (!currentState.isOperational()) {
                System.out.println("Elevator currently not operational");
                break;
            }
            else {
                if (!schedulingStrategy.hasNext()) {
                    System.out.println("No more requests scheduled, going idle");
                    currentState = new IdleState();
                    // CHANGE: Make the thread sleep unstead of busy waiting so that CPU cycles are not wasted
                    try {
                        Thread.sleep(500);
                    }
                    catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                else {
                    Integer nextFloor = schedulingStrategy.next(currentState).orElse(null);
                    if (nextFloor == null) {
                        System.out.println("No more requests scheduled, going idle");
                        currentState = new IdleState();
                        continue;
                    }
                    // Not excepted since scheduling strategies are handling this, but still handle it
                    else if (currentFloor == nextFloor) {
                        System.out.println("Request for current floor itself");
                        continue;
                    }

                    else if (nextFloor > currentFloor) currentState = new MovingUpState();
                    else currentState = new MovingDownState();
                    move(currentFloor, nextFloor);

                    System.out.println("Arrived at floor: " + currentFloor);
                }
            }
        }
    }

    // UPDATE: Add validation to check if floor is valif before pasing it onto scheduling strategy
    public void addRequest(int requestedFloor) {
        if (requestedFloor < minFloor || requestedFloor > maxFloor) {
            throw new IllegalArgumentException("Requested floor out of bounds: "+ requestedFloor);
        }
        schedulingStrategy.addRequest(currentFloor, requestedFloor);
    }

    public int getCurrentFloor() {
        return currentFloor;
    }

    // UPDATE: Add method for graceful shutdown
    public void shutdown() {
        running = false;
        System.out.println("Shutdown initiated");
    }

    // UPDATE: Add method to set/unset maintenance mode
    public void setMaintenance(boolean maintenance) {
        if (maintenance) {
            currentState = new MaintenanceState();
            System.out.println(String.format("[%s] Entering maintenance mode", id));
        }
        else {
            currentState = new IdleState();
            System.out.println(String.format("[%s] Exiting maintenance mode", id));
        }
    }
}

interface SchedulingStrategy {
    public void addRequest(int currentFloor, int requestedFloor);
    public boolean hasNext();
    public Optional <Integer> next(ElevatorState currentState);
}

// For now, single threaded context so mid flight requests cannot come in
class ScanSchedulingStrategy implements SchedulingStrategy {
    // UPDATE: Using TreeSet for deduplication instead of priority queue
    private final TreeSet <Integer> upRequests = new TreeSet<>();
    private final TreeSet <Integer> downRequests = new TreeSet<>(Collections.reverseOrder());

    @Override
    public void addRequest(int currentFloor, int requestedFloor) {
        if (currentFloor == requestedFloor) throw new IllegalArgumentException("Elevator at same floor as requested floor");
        // UPDATED: Automatic deduplication via TreeSet
        else if (currentFloor < requestedFloor) upRequests.add(requestedFloor);
        else downRequests.add(requestedFloor);
    }

    @Override
    public boolean hasNext() {
        return !upRequests.isEmpty() || !downRequests.isEmpty();
    }

    // Adjusting the current state of the elevator based on the next floor will be done by the orchestrator based on the next floor and current floor comparison
    @Override
    public Optional <Integer> next(ElevatorState currentState) {
        ElevatorStatus elevatorStatus = currentState.getStatus();

        switch (elevatorStatus) {
            case IDLE:
                if (!upRequests.isEmpty()) return Optional.of(upRequests.pollFirst()); // Arbitrarily giving preference to moving up
                else if (!downRequests.isEmpty()) return Optional.of(downRequests.pollFirst());
                break; // UPDATE: Add missing break to
            case MOVING_UP:
                if (!upRequests.isEmpty()) return Optional.of(upRequests.pollFirst());
                else if (!downRequests.isEmpty()) return Optional.of(downRequests.pollFirst());
                break;
            case MOVING_DOWN:
                if (!downRequests.isEmpty()) return Optional.of(downRequests.pollFirst());
                else if (!upRequests.isEmpty()) return Optional.of(upRequests.pollFirst());
                break;
        }
        return Optional.empty();
    }
}

class FCFSSchedulingStrategy implements SchedulingStrategy {
    // UPDATE: Use LinkedHashSet instead of LinkedList to maintain FIFO order as well as have deduplication in place
    private final Set <Integer> requests = new LinkedHashSet<>();

    @Override
    public void addRequest(int currentFloor, int requestedFloor) {
        if (currentFloor == requestedFloor) throw new IllegalArgumentException("Elevator at same floor as requested floor");
        else requests.add(requestedFloor);
    }

    @Override
    public boolean hasNext() {
        return !requests.isEmpty();
    }

    @Override
    public Optional <Integer> next(ElevatorState currentState) {
        if (requests.isEmpty()) return Optional.empty();
        Integer floor = requests.iterator().next();
        requests.remove(floor);
        return Optional.of(floor);
    }
}


public class Main {
    public static void main(String [] args) {
        // UPDATED: Added demonstration code for single-threaded execution
        System.out.println("=== Elevator System Demo (Single-Threaded) ===\n");

        // Demo 1: SCAN Strategy
        System.out.println("--- Demo 1: SCAN Strategy ---");
        Elevator scanElevator = new Elevator(
                "ELV-SCAN",
                0,      // min floor
                10,     // max floor
                0,      // initial floor
                new ScanSchedulingStrategy()
        );

        // Add requests before execution
        scanElevator.addRequest(5);
        scanElevator.addRequest(3);
        scanElevator.addRequest(7);
        scanElevator.addRequest(1);
        scanElevator.addRequest(9);

        // Run elevator in separate thread for demo purposes
        Thread scanThread = new Thread(() -> scanElevator.execute());
        scanThread.start();

        // Wait for completion
        try {
            Thread.sleep(15000);
            scanElevator.shutdown();
            scanThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("\n--- Demo 2: FCFS Strategy ---");
        // Demo 2: FCFS Strategy
        Elevator fcfsElevator = new Elevator(
                "ELV-FCFS",
                0,
                10,
                0,
                new FCFSSchedulingStrategy()
        );

        fcfsElevator.addRequest(7);
        fcfsElevator.addRequest(3);
        fcfsElevator.addRequest(9);
        fcfsElevator.addRequest(1);

        Thread fcfsThread = new Thread(() -> fcfsElevator.execute());
        fcfsThread.start();

        try {
            Thread.sleep(15000);
            fcfsElevator.shutdown();
            fcfsThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("\n=== Demo Complete ===");


    }
}