import java.util.*;

// CHANGE: Add Direction enum to denote the current direction in which the elevator is travelling
enum Direction {
    UP, DOWN, IDLE
};

// CHANGE: instanceof checks are not recommended. Instead have an abstract method getDirection()
sealed interface ElevatorState permits IdleState, MovingUpState, MovingDownState, MaintenanceState {
    public boolean isOperational();
    public Direction getDirection();
};

// CHANGE: Provide implementation for getDirection()
final class IdleState implements ElevatorState {
    @Override
    public boolean isOperational() {
        return true;
    }

    @Override
    public Direction getDirection() {
        return Direction.IDLE;
    }
};

// CHANGE: Provide implementation for getDirection()
final class MovingUpState implements ElevatorState {
    @Override
    public boolean isOperational() {
        return true;
    }

    @Override
    public Direction getDirection() {
        return Direction.UP;
    }
};

// CHANGE: Provide implementation for getDirection()
final class MovingDownState implements ElevatorState {
    @Override
    public boolean isOperational() {
        return true;
    }

    @Override
    public Direction getDirection() {
        return Direction.DOWN;
    }
};

// CHANGE: Provide implementation for getDirection()
final class MaintenanceState implements ElevatorState {
    @Override
    public boolean isOperational() {
        return false;
    }

    @Override
    public Direction getDirection() {
        return Direction.IDLE;
    }
}

interface SchedulerStrategy {
    // CHANGE: Changed from addRequest(Elevator elevator, int floor) to addRequest(int requestedFloor, int currentFloor) - the method takes only necessary data
    public void addRequest(int requestedFloor, int currentFloor);
    public boolean hasNext();
    /*
        CHANGE: Changed from getNextFloor(Elevator elevator) to getNextFloor(int currentFloor, Direction currDirection)
        Given the current direction (eg: UP) and the current floor where elevator is (eg: 7), return me the next floor (eg: 9)
        IMPORTANT: Previously, this method was both returning the next floor as well as changing the state (eg: from UP to DOWN), which was violation of SRP.
        Now, this method only takes care of scheduling the next floor, state management is taken by orchestrator
    */

    public Optional<Integer> getNextFloor(int currentFloor, Direction currDirection);
};

class ScanElevatorScheduler implements SchedulerStrategy {
    // CHANGE: Declare as private final
    private final PriorityQueue<Integer> minHeap = new PriorityQueue<>();
    // CHANGE: Changed from new PriorityQueue<>((Integer val1, Integer val2) -> val2 - val1); to new PriorityQueue<>((val1,val2) -> val2 - val1); since the type is already inefrred from the LHS
    private final PriorityQueue<Integer> maxHeap = new PriorityQueue<>((val1, val2) -> val2 - val1);

    @Override
    // CHANGE: To adhere to Single responsibility principle, scheduler will not be directly reading/modifying the state. Instead, orchestrator will take care of the state related operations
    public void addRequest(int requestedFloor, int currentFloor) {
        if (requestedFloor == currentFloor) {
            System.out.println("Already at the requested floor");
            return;
        }
        else if (requestedFloor > currentFloor) minHeap.add(requestedFloor);
        else if (requestedFloor < currentFloor) maxHeap.add(requestedFloor);
    }

    @Override
    public boolean hasNext() {
        return !minHeap.isEmpty() || !maxHeap.isEmpty();
    }

    @Override
    // CHANGE: Remove the instanceof checks, the dirction input required to produce the output is now being passed as an argument
    public Optional<Integer> getNextFloor(int currentFloor, Direction currentDirection) {
        switch (currentDirection) {
            case UP:
                if (!minHeap.isEmpty()) return Optional.of(minHeap.poll());
                else if (!maxHeap.isEmpty()) return Optional.of(maxHeap.poll());
                break;

            case DOWN:
                if (!maxHeap.isEmpty()) return Optional.of(maxHeap.poll());
                else if (!minHeap.isEmpty()) return Optional.of(minHeap.poll());
                break;

            case IDLE:
                // No preference: serve up requests first (arbitrary choice)
                if (!minHeap.isEmpty()) return Optional.of(minHeap.poll());
                else if (!maxHeap.isEmpty()) return Optional.of(maxHeap.poll());
                break;
        }
        // in all cases where the elevator needs to remain idle, return empty
        return Optional.empty();
    }
};

class ElevatorController {
    // CHANGE: Use set instead of list for O(1) lookups
    private final Set <Elevator> elevators;

    public ElevatorController(List <Elevator> elevators) {
        // CHANGE: Add null checks in the constructor
        if (elevators == null || elevators.isEmpty()) {
            throw new IllegalArgumentException("Elevators cannot be empty");
        }
        this.elevators = new HashSet<>(elevators);
    }

    public void placeRequest(Elevator elevator, int requestedFloor) {
        // CHANGE: Since the elevators collection has been changed from a list to a set, lookups will now be O(1)
        if (!elevators.contains(elevator)) throw new IllegalArgumentException("Requested elevator not managed by current controller");
        if (!elevator.validateFloorWithinRange(requestedFloor)) throw new IllegalArgumentException("Requested floor not within range");
        SchedulerStrategy schedulerStrategy = elevator.getSchedulerStrategy();
        // pass only required data, not entire Elevator object (loose coupling)
        schedulerStrategy.addRequest(requestedFloor, elevator.getCurrentFloor());
    }

    // CHANGE: Simplified move method with Integer.compare for direction
    private void move(Elevator elevator, int currentFloor, int nextFloor) {
       int step = Integer.compare(nextFloor, currentFloor);  // returs either of -1,0,1 depending on the sign of nextFloor-currentFloor
       int iterator = currentFloor;
       while (iterator != nextFloor) {
           iterator += step;
           System.out.println("Elevator: " + elevator.getId() + " moving to floor: " + iterator);
           elevator.setCurrentFloor(iterator);
       }
    }

    // CHANGE: Single responsibility principle, elevator scheduler should only be responsible for managing the scheduling
    // Change in states of the elevator (UP, DOWN etc) should be controlled by the controller/orchestration layer
    public void execute(Elevator elevator) {
        System.out.println("\n == Starting execution for elevator: "+ elevator.getId() + " ===");

        while (true) {
            int currentFloor = elevator.getCurrentFloor();
            if (!elevator.getState().isOperational()) {
                System.out.println("Elevator currently not operational");
                break;
            } else {
                SchedulerStrategy schedulerStrategy = elevator.getSchedulerStrategy();
                if (!schedulerStrategy.hasNext()) {
                    System.out.println("Elevator has no pending requests");
                    elevator.setState(new IdleState());
                    break;
                } else {
                    Optional<Integer> nextFloor = schedulerStrategy.getNextFloor(currentFloor, elevator.getState().getDirection());
                    if (nextFloor.isPresent()) {
                        int targetFloor = nextFloor.get();
                        System.out.println("Preparing to move to floor : " + targetFloor);

                        // Infer direction based on current and next floor, set state accordingly
                        if (targetFloor > currentFloor) {
                            System.out.println("Direction: MOVING UP");
                            elevator.setState(new MovingUpState());
                        } else if (targetFloor < currentFloor) {
                            System.out.println("Direction: MOVING DOWN");
                            elevator.setState(new MovingDownState());
                        } else {
                            // Should not happen as scheduler already filters, but handle it
                            System.out.println("Already at target floor");
                            continue;
                        }
                        move(elevator, currentFloor, targetFloor);
                        System.out.println("Arrived at floor: " + targetFloor);
                    }
                }
            }
        }
        System.out.println("=== Execution complete for Elevator: " + elevator.getId() + " ===\n");
    }
};


class Elevator {
    private final String id;
    private final int minFloor;
    private final int maxFloor;
    private int currentFloor;
    private ElevatorState state;
    private final SchedulerStrategy schedulerStrategy;

    public boolean validateFloorWithinRange(int floor) {
        return floor>=minFloor && floor<=maxFloor;
    }

    // CHANGE: Add null safety checks in constructor
    public Elevator(String id, int minFloor, int maxFloor, int initialFloor, SchedulerStrategy schedulerStrategy) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Elevator ID cannot be empty");
        }
        if (schedulerStrategy == null) {
            throw new IllegalArgumentException("Scheduler strategy cannot be null");
        }
        // CHANGE: Add validation to check that max floor >= min floor
        if (maxFloor < minFloor) {
            throw new IllegalArgumentException("Min floor cannot be greater than max floor");
        }
        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
        if (!validateFloorWithinRange(initialFloor)) throw new IllegalArgumentException("Initial floor not within range");
        this.id = id;
        this.currentFloor = initialFloor;
        this.state = new IdleState();
        this.schedulerStrategy = schedulerStrategy;
    }

    public String getId() {
        return id;
    }

    public ElevatorState getState() {
        return state;
    }

    public int getCurrentFloor() {
        return currentFloor;
    }

    // Change: Add validation in setter
    public void setCurrentFloor(int currentFloor) {
        if (!validateFloorWithinRange(currentFloor)) throw new IllegalArgumentException("Current floor not within range");
        this.currentFloor = currentFloor;
    }

    public SchedulerStrategy getSchedulerStrategy() {
        return schedulerStrategy;
    }

    public void setState(ElevatorState state) {
        this.state = state;
    }

    // Override equals() and hashCode() methods for proper set behaviour (based on ID)
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Elevator)) return false;
        Elevator other = (Elevator) o;
        return other.id.equals(this.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
};

public class Main {
    public static void main(String [] args) {
        System.out.println("========================================");
        System.out.println("  Elevator System Simulation Started");
        System.out.println("========================================\n");

        // Test Case 1: Basic SCAN scheduling with upward requests
        System.out.println("TEST CASE 1: Basic upward movement with SCAN scheduler");
        System.out.println("------------------------------------------------------");
        Elevator elevator1 = new Elevator("E1", 0, 10, 0, new ScanElevatorScheduler());
        ElevatorController controller1 = new ElevatorController(List.of(elevator1));

        controller1.placeRequest(elevator1, 3);
        controller1.placeRequest(elevator1, 7);
        controller1.placeRequest(elevator1, 5);
        controller1.execute(elevator1);

        // Test Case 2: Mixed upward and downward requests
        System.out.println("\nTEST CASE 2: Mixed up and down requests");
        System.out.println("----------------------------------------");
        Elevator elevator2 = new Elevator("E2", 0, 15, 5, new ScanElevatorScheduler());
        ElevatorController controller2 = new ElevatorController(List.of(elevator2));

        controller2.placeRequest(elevator2, 10);  // Up from 5
        controller2.placeRequest(elevator2, 2);   // Down from 5
        controller2.placeRequest(elevator2, 12);  // Up
        controller2.placeRequest(elevator2, 1);   // Down
        controller2.execute(elevator2);

        // Test Case 3: Requests only below current floor
        System.out.println("\nTEST CASE 3: Only downward requests");
        System.out.println("------------------------------------");
        Elevator elevator3 = new Elevator("E3", 0, 20, 15, new ScanElevatorScheduler());
        ElevatorController controller3 = new ElevatorController(List.of(elevator3));

        controller3.placeRequest(elevator3, 10);
        controller3.placeRequest(elevator3, 5);
        controller3.placeRequest(elevator3, 8);
        controller3.execute(elevator3);

        // Test Case 4: Elevator in maintenance state
        System.out.println("\nTEST CASE 4: Elevator in maintenance mode");
        System.out.println("------------------------------------------");
        Elevator elevator4 = new Elevator("E4", 0, 10, 3, new ScanElevatorScheduler());
        ElevatorController controller4 = new ElevatorController(List.of(elevator4));

        controller4.placeRequest(elevator4, 7);
        controller4.placeRequest(elevator4, 9);

        // Set to maintenance before execution
        elevator4.setState(new MaintenanceState());
        controller4.execute(elevator4);

        // Test Case 5: Multiple elevators
        System.out.println("\nTEST CASE 5: Multiple elevators managed by one controller");
        System.out.println("----------------------------------------------------------");
        Elevator elevatorA = new Elevator("A", 0, 10, 0, new ScanElevatorScheduler());
        Elevator elevatorB = new Elevator("B", 0, 10, 10, new ScanElevatorScheduler());
        ElevatorController multiController = new ElevatorController(List.of(elevatorA, elevatorB));

        multiController.placeRequest(elevatorA, 5);
        multiController.placeRequest(elevatorA, 8);
        multiController.placeRequest(elevatorB, 3);
        multiController.placeRequest(elevatorB, 1);

        multiController.execute(elevatorA);
        multiController.execute(elevatorB);

        // Test Case 6: Edge cases - requesting same floor
        System.out.println("\nTEST CASE 6: Requesting current floor");
        System.out.println("--------------------------------------");
        Elevator elevator5 = new Elevator("E5", 0, 10, 5, new ScanElevatorScheduler());
        ElevatorController controller5 = new ElevatorController(List.of(elevator5));

        controller5.placeRequest(elevator5, 5);  // Same floor
        controller5.placeRequest(elevator5, 7);
        controller5.execute(elevator5);

        // Test Case 7: Exception handling - invalid floor
        System.out.println("\nTEST CASE 7: Exception handling for invalid floor");
        System.out.println("--------------------------------------------------");
        Elevator elevator6 = new Elevator("E6", 0, 10, 5, new ScanElevatorScheduler());
        ElevatorController controller6 = new ElevatorController(List.of(elevator6));

        try {
            controller6.placeRequest(elevator6, 15);  // Out of range
        } catch (IllegalArgumentException e) {
            System.out.println("Caught expected exception: " + e.getMessage());
        }

        System.out.println("\n========================================");
        System.out.println("  All Test Cases Completed!");
        System.out.println("========================================");

    }
};