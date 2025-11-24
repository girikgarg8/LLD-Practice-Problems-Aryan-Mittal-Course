import java.util.*;

class Pair {
    private final int x;
    private final int y;

    public Pair(int x, int y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x,y);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Pair)) return false;
        Pair other = (Pair) obj;
        return other.x == this.x && other.y == this.y;
    }

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    @Override
    public String toString() {
        return "Pair {x= " + x + ", y= " + y + " }";
    }
}

enum GameState {
    IN_PROGRESS, OVER
}

class MoveResult {
    private final GameState state;
    private final String message;

    public MoveResult(GameState state, String message) {
        this.state = state;
        this.message = message;
    }

    public GameState getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }
}

enum Direction {
    UP(new Pair(0,1)),
    DOWN(new Pair(0,-1)),
    LEFT(new Pair(-1,0)),
    RIGHT(new Pair(1,0));

    Pair delta;

    Direction(Pair delta) {
        this.delta = delta;
    }

    public Pair getDelta() {
        return delta;
    }
}

class Snake {
    private final Deque <Pair> body;
    private final Set <Pair> bodyLocations;
    private Direction currentDirection;

    public Snake() {
        body = new ArrayDeque<>();
        bodyLocations = new HashSet<>();
        Pair initialLocation = new Pair(0,0);
        body.addLast(initialLocation);
        bodyLocations.add(initialLocation); // UPDATED: Add the initial 0,0 cell to body locations set as well
        currentDirection = Direction.RIGHT;
    }

    public Direction getCurrentDirection() {
        return currentDirection;
    }

    public void setCurrentDirection(Direction direction) {
        this.currentDirection = direction;
    }

    public void addToHead(Pair pair) {
        body.addFirst(pair);
        bodyLocations.add(pair);
    }

    public void removeTail() {
        Pair tail = body.pollLast();
        bodyLocations.remove(tail);
    }

    public Deque <Pair> getBody() {
        return new ArrayDeque<>(body);
    }

    public boolean isPartOfBody(Pair pair) {
        return bodyLocations.contains(pair);
    }

    public Pair getBodyHead() {
        return body.peekFirst();
    }

    public Pair getBodyTail() {
        return body.peekLast();
    }

    public int getBodyLength() {
        return body.size();
    }

    // UPDATE: Helpful for printing current snake state
    @Override
    public String toString() {
        return "Snake {length=" + body.size() + ", body=" + body + "}";
    }
};

class Game {
    private final int n;
    private final Snake snake;
    private final Set <Pair> foodLocations;

    public Game(Snake snake, int n) {
        if (snake == null) throw new IllegalArgumentException("Snake cannot be null");
        if (n <= 1) throw new IllegalArgumentException("Grid should be at least 2*2");
        this.snake = snake;
        this.n = n;
        this.foodLocations = new HashSet<>();
        spawnFood(4);
    }

    private void spawnFood(int count) {
        List <Pair> freeLocations = new ArrayList<>();
        for (int i=0; i<n; i++) {
            for (int j=0; j<n; j++) {
                Pair pair = new Pair(i,j);
                if (!snake.isPartOfBody(pair) && !foodLocations.contains(pair)) freeLocations.add(pair);
            }
        }

        for (int i=0; i<Math.min(count, freeLocations.size()); i++) foodLocations.add(freeLocations.get(i));
    }

    private void consumeFood(Pair location) {
        if (foodLocations.remove(location)) spawnFood(1);
    }

    private boolean isFoodLocation(Pair location) {
        return foodLocations.contains(location);
    }

    private boolean isWithinGridBounds(Pair location) {
        int x = location.getX();
        int y = location.getY();
        return x>=0 && x<n && y>=0 && y<n;
    }

    private boolean isMoveAllowed(Direction currentDirection, Direction newDirection) {
        boolean isReverseMove = (currentDirection.getDelta().getX() + newDirection.getDelta().getX()) == 0 && (currentDirection.getDelta().getY() + newDirection.getDelta().getY()) == 0;
        return !isReverseMove || snake.getBodyLength() == 1;
    }

    public MoveResult move(Direction direction) {
        if (!isMoveAllowed(snake.getCurrentDirection(), direction)) {
            throw new IllegalArgumentException("Move not allowed");
        }

        Pair head = snake.getBodyHead();
        Pair updatedHead = new Pair(head.getX() + direction.getDelta().getX(), head.getY() + direction.getDelta().getY());

        if (!isWithinGridBounds(updatedHead)) return new MoveResult(GameState.OVER, "Snake's head hit the wall");

        if (!isFoodLocation(updatedHead)) snake.removeTail();
        else consumeFood(updatedHead);

        if (snake.isPartOfBody(updatedHead)) return new MoveResult(GameState.OVER, "Collision with snake's own body");
        snake.addToHead(updatedHead);
        snake.setCurrentDirection(direction);
        return new MoveResult(GameState.IN_PROGRESS, "Move accepted");
    }

    // UPDATED: Visibility for UI/logging
    public Set <Pair> getFoodLocations() {
        return new HashSet<>(foodLocations);
    }

    // UPDATED: Allows game controller to inspect state
    public Snake getSnake() {
        return snake;
    }
}

class GameController {
    private final Scanner scanner;
    private final Game game;

    private Direction parseInput(String input) {
        // UPDATE: Null safety
        if (input == null) return null;

        return switch(input.trim().toUpperCase()) {
            case "U" -> Direction.UP;
            case "D" -> Direction.DOWN;
            case "L" -> Direction.LEFT;
            case "R" -> Direction.RIGHT;
            default -> null;
        };
    }

    public String readInput(String prompt) {
        System.out.println(prompt);
        return scanner.nextLine();
    }

    public GameController(Game game) {
        if (game == null) throw new IllegalArgumentException("Game cannot be null");
        this.game = game;
        scanner = new Scanner(System.in);
    }

    public void play() {
        try {
            while (true) {
                // UPDATED: Print current snake and food state for observability
                System.out.println(game.getSnake());
                System.out.println("Food: "+ game.getFoodLocations());

                String input = readInput("Please enter your direction: (U/D/L/R)");
                Direction direction = parseInput(input);
                if (direction == null) {
                    System.out.println("Invalid direction input, please try again");
                    continue;
                }
                try {
                    MoveResult moveResult = game.move(direction);
                    if (moveResult.getState() == GameState.OVER) {
                        System.out.println("Game over: "+ moveResult.getMessage());
                        break;
                    }
                    else {
                        System.out.println(moveResult.getMessage()); // UPDATED: acknowledge progress
                    }
                }
                catch (IllegalArgumentException ex) {
                    System.out.println("Move couldn't be completed due to exception: " + ex);
                    continue;
                }
            }
        }
        // UPDATED: Close the scanner after exiting the while loop, not on every while loop iteration
         finally {
            scanner.close();
        }
    }
}

public class Main {
    public static void main(String [] args) {
        Snake snake = new Snake();
        Game game = new Game(snake, 10);
        GameController controller = new GameController(game);
        controller.play();
    }
}