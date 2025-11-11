import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

// CHANGE: record already has equals/hashCode/toString; clone not needed because records are immutable
record Pair(int x, int y) {

};

// CHANGE: renamed to delta
enum Direction {
    UP(new Pair(0,1)),
    DOWN(new Pair(0,-1)),
    LEFT(new Pair(-1,0)),
    RIGHT(new Pair(1,0));

    private Pair delta;

    Direction(Pair delta) {
        this.delta = delta;
    }

    public Pair getDelta() {
        return delta;
    }
};

// CHANGE: Renamed set to occupied
class Snake {
    private final Deque<Pair> body = new ArrayDeque<>();
    private final Set <Pair> occupied = new HashSet<>();

    public Snake() {
        Pair initialHead = new Pair(0,0);
        body.add(initialHead);
        occupied.add(initialHead);
    }

    public Pair getHead() {
        return body.peekFirst();
    }

    // CHANGE: Instead of client having the logic of enqueuing the new head to front and dequeueing feom the back, we can expose methods in Snake class itself

    // CHANGE: Method-1: No food at the new head location, insert the new head in the front of deque and remove tail from back
    public void moveTo(Pair newHead) {
        body.addFirst(newHead);
        occupied.add(newHead);
        Pair tail = body.pollLast();
        occupied.remove(tail);
    }

    // CHANGE: Method-2 : Food at the new head location, insert the new head in deque front, no action required at tail
    public void growTo(Pair newHead) {
        body.addFirst(newHead);
        occupied.add(newHead);
    }

//    public void replaceBodyHead(Pair head) {
//        body.addFirst(head);
//        occupied.add(head);
//    }
//
//    public void removeTail() {
//        Pair tail = body.pollLast();
//        occupied.remove(tail);
//    }

    public boolean occupies(Pair pair) {
        return occupied.contains(pair);
    }

    // CHANGE: If there is no in-built method in Java to return the deque deep copy, we can return an unmodifiable list instead. Client should be okay to receive list as well since their usecase is only iterating over the list, not add/delete
    public List<Pair> getBody() {
        return Collections.unmodifiableList(new ArrayList<>(body));
    }

    public Pair peekTail() {
        return body.peekLast();
    }
};

// CHANGE: Replace run() with step() that advances one tick. Main loop reads input, sets direction, calls step().
enum StepResult {
    CONTINUE,
    GAME_OVER
};

// CHANGE: Use ThreadLocalRandom
// Issue: Double-checked Random singleton adds complexity and contention potential.
// Benefit: Simpler, faster, thread-friendly random calls.

/*
    class RandomGeneratorSingleton {
    private static volatile Random instance;

    public static Random getInstance() {
        if (instance == null) {
            synchronized(RandomGeneratorSingleton.class) {
                if (instance == null) instance = new Random();
            }
        }
        return instance;
    }
};
*/

//        Issue: Food can spawn on the snake or overlap existing food; initial 4 foods may be fewer due to duplicates.
//        Benefit: Guaranteed valid spawn, no accidental overlaps, and predictable behavior.
//        Change: Choose a random free cell from the board (uniformly)

class GameContext {
    private final Snake snake;
    private final int n;
    private Direction currentDirection;
    private final Set <Pair> foodLocations;

    public GameContext(Snake snake, int n) {
        this.snake = snake;
        this.n = n;
        currentDirection = Direction.RIGHT;
        foodLocations = new HashSet<>();

        // spawn initial food on free slots only
        spawnFood(4);
    }

//    private void populateFoodInBoard() {
//        Random random = RandomGeneratorSingleton.getInstance();
//        int x = random.nextInt(n); // 0 inclusive and 1 exclusive
//        int y = random.nextInt(n);
//        foodLocations.add(new Pair(x,y));
//    }

    private boolean checkCellHasFood(Pair pair) {
        return foodLocations.contains(pair);
    }

    private boolean withinBounds(Pair pair) {
        int x = pair.x();
        int y = pair.y();
        return x>=0 && x<n && y>=0 && y<n;
    }

    // Uniformly choose a random free cell (not occupied by snake or existing food)
    private Optional <Pair> randomFreeCell() {
        List <Pair> free = new ArrayList<>(n*n);
        Set <Pair> occupied = new HashSet<>(foodLocations);
        occupied.addAll(snake.getBody());

        for (int i=0;i<n;i++) {
            for (int j=0;j<n;j++) {
                Pair p = new Pair(i,j);
                if (!occupied.contains(p)) free.add(p);
            }
        }

        if (free.isEmpty()) return Optional.empty();
        int idx = ThreadLocalRandom.current().nextInt(free.size()); // 0 inclusive, n exclusive
        return Optional.of(free.get(idx));
    }

    private void spawnFood(int count) {
        for (int i=0;i<count;i++) {
            Optional <Pair> cell = randomFreeCell();
            if (cell.isPresent()) {
                foodLocations.add(cell.get());
            }
            else break; // Board is full, no more free slot
        }
    }

    private Pair getNewHeadCoordinates() {
        Pair currentHeadCoordinates = snake.getHead();
        int newHeadX = currentHeadCoordinates.x() + currentDirection.getDelta().x();
        int newHeadY = currentHeadCoordinates.y() + currentDirection.getDelta().y();
        return new Pair(newHeadX, newHeadY);
    }

//    private void refreshFoodLocation(Pair currLocation) {
//        foodLocations.remove(currLocation);
//        populateFoodInBoard();
//    }

    // Advance one tick: compute next head, check wall/body, then move or grow
    public StepResult step() {
        Pair newHead = getNewHeadCoordinates();

        // wall collision
        if (!withinBounds(newHead)) {
            System.out.println("Collision with wall, Game ended");
            return StepResult.GAME_OVER;
        }

        boolean eats = foodLocations.contains(newHead);

        if (!eats) {
            // while checking the collision with snake's own body, we need to make sure that we don't check against tail, since tail is going to get removed
            Pair tail = snake.peekTail();
            boolean hitsBody = snake.occupies(newHead) && !newHead.equals(tail);

            if (hitsBody) {
                System.out.println("Collision with snake's own body, game ended");
                return StepResult.GAME_OVER;
            }
            snake.moveTo(newHead);
            return StepResult.CONTINUE;
        }
        else {
            // at the new head location, food is present. So, the snake's tail need not be removed
            if (snake.occupies(newHead)) {
                System.out.println("Collision with snake's own body, game ended");
                return StepResult.GAME_OVER;
            }

            snake.growTo(newHead);
            foodLocations.remove(newHead);
            spawnFood(1); // maintain food count
            return StepResult.CONTINUE;
        }
    }

//    public boolean run() {
//        while (true) {
//            print();
//            Pair newHeadCoordinates = getNewHeadCoordinates();
//
//            if (checkCellHasFood(newHeadCoordinates)) {
//                if (!checkIfWithinBounds(newHeadCoordinates)) {
//                    System.out.println("Collision will wall, game ended");
//                    break;
//                }
//                if (snake.isPairExistInBody(newHeadCoordinates)) {
//                    System.out.println("Collision with snake's own body, game ended");
//                    break;
//                }
//                snake.replaceBodyHead(newHeadCoordinates);
//                refreshFoodLocation(newHeadCoordinates);
//            }
//            else {
//                if (!checkIfWithinBounds(newHeadCoordinates)) {
//                    System.out.println("Collision will wall, game ended");
//                    return false;
//                }
//                snake.removeTail();
//                if (snake.isPairExistInBody(newHeadCoordinates)) {
//                    System.out.println("Collision with snake's own body, game ended");
//                    return false;
//                }
//                snake.replaceBodyHead(newHeadCoordinates);
//            }
//        }
//        return true;
//    }

    public void setCurrentDirection(Direction direction) {
        // Prevent 180-degree turn if snake length > 1 (optional safety)
        if (!canReverse() && isReverse(currentDirection, direction)) return ;
        this.currentDirection = direction;
    }

    private boolean canReverse() {
        // if the snake length is 1, reversing is harmless; otherwise disallow reversing into itself
        return snake.getBody().size() == 1;
    }

    private boolean isReverse(Direction a, Direction b) {
        int aDx = a.getDelta().x();
        int aDy = a.getDelta().y();
        int bDx = b.getDelta().x();
        int bDy = b.getDelta().y();

        return (aDx + bDx) == 0 && (aDy+bDy) == 0;
    }

    public void printState() {
        List <Pair> snakeBody = snake.getBody();
        System.out.println("Snake body is: "+ snakeBody);
        System.out.println("Food locations are: " + foodLocations);
    }
};

public class Main {

    private static Direction parseDirection(String input) {
        String s = input.trim().toUpperCase();

        return switch(input) {
            case "U" -> Direction.UP;
            case "D" -> Direction.DOWN;
            case "L" -> Direction.LEFT;
            case "R" ->  Direction.RIGHT;
            default -> null;
        };
    }

    public static void main(String [] args) {

        Snake snake = new Snake();
        GameContext gameContext = new GameContext(snake, 5);
        Scanner scanner = new Scanner(System.in);

        gameContext.printState();
        // CHANGE: Instead of doing enum validation with exception handling, write a switch expression to return the enum value against input string.
        // If input string doesn't match any enum value, return null and handle it accordingly in the caller

//        while (true) {
//            try {
//                if (!gameResult) break;
//                Direction direction = Direction.valueOf(scanner.nextLine());
//                gameContext.setCurrentDirection(direction);
//            }
//            catch (IllegalArgumentException e) {
//                System.out.println("Invalid input passed, please pass valid input");
//            }
//        }

          while (true) {
              System.out.println("Enter direction: (U/L/D/R) ");
              String line = scanner.nextLine();
              Direction dir = parseDirection(line);
              if (dir == null) {
                  System.out.println("Invalid input passed, please try again");
                  continue;
              }
              gameContext.setCurrentDirection(dir);
              StepResult stepResult = gameContext.step();
              gameContext.printState();
              if (stepResult == StepResult.GAME_OVER) break;
          }
    }
}