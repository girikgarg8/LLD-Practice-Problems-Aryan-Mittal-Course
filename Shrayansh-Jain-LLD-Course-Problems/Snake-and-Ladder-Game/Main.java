import java.util.*;
import java.util.concurrent.*;

class Player {
    private final String id;
    private final String name;

    public Player(String id, String name) {
        // CHANGE: Added validation for null and empty check
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Player ID cannot be null or empty");
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Name cannot be null or empty");
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    // Change: Added equals and hashCode for proper map usage
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Player)) return false;
        Player other = (Player) obj;
        return this.id.equals(other.id);
    }
};

interface MoveGenerationStrategy {
    public int next();
};

class DiceMoveGenerationStrategy implements MoveGenerationStrategy {
    private static final int MIN_DICE_MOVE = 1;
    private static final int MAX_DICE_MOVE = 6; // CHANGE: Fixed from 7 to 6 (standard dice)

    @Override
    public int next() {
        // Update: Fixed to include MAX_DICE_MOVE by adding 1
        return ThreadLocalRandom.current().nextInt(MIN_DICE_MOVE, MAX_DICE_MOVE + 1);
    }
};

// CHANGE: Add custom exception class for better error handling
class GameException extends RuntimeException {
    public GameException(String message) {
        super(message);
    }
};

class GameContext {
    private final Map <Player, Integer> positions;
    private final List <Player> players;
    private final Map <Integer, Integer> snakes;
    private final Map <Integer, Integer> ladders;
    private int currentPlayerIndex;
    private final int winningState;
    private final MoveGenerationStrategy moveGenerationStrategy;
    private static final int STARTING_POSITION = 1;

    private void validateInputs(List <Player> players, int winningState, Map <Integer, Integer> snakes, Map<Integer, Integer> ladders, MoveGenerationStrategy moveGenerationStrategy) {
        if (players == null || players.isEmpty()) throw new GameException("Players list cannot be null or empty");
        if (players.size() < 2) throw new GameException("At least 2 players are required");
        if (winningState <= STARTING_POSITION) throw new GameException("Winning state must be greater than starting position");
        if (snakes == null || ladders == null) throw new GameException("Snakes and ladders map cannot be null");
        if (moveGenerationStrategy == null) throw new GameException("Move generation strategy cannot be null");

        for (Map.Entry <Integer, Integer> snake: snakes.entrySet()) {
            int head = snake.getKey();
            int tail = snake.getValue();
            if (head <= STARTING_POSITION || head >= winningState) throw new GameException("Snake head " + head + " is out of bounds");
            if (tail <= 0 || tail >= head) throw new GameException("Invalid snake: head= " + head + ", tail =" + tail);
        }

        for (Map.Entry <Integer, Integer> ladder: ladders.entrySet()) {
            int bottom = ladder.getKey();
            int top = ladder.getValue();
            if (bottom <= STARTING_POSITION || bottom >= winningState) throw new GameException("Ladder bottom: " + bottom+ " is out of bounds");
            if (top <= bottom || top > winningState) throw new GameException("Invalid ladder: bottom=" + bottom + ", top=" + top);
        }

        for (Integer position: snakes.keySet()) {
            if (ladders.containsKey(position)) throw new GameException("Position: " + position + " has both snake and ladder");
        }
    }

    public GameContext(List <Player> players, int winningState, Map <Integer, Integer> snakes, Map <Integer, Integer> ladders, MoveGenerationStrategy moveGenerationStrategy) {
        // CHANGE: Add comprehensive set of validations for all the parameters
        validateInputs(players, winningState, snakes, ladders, moveGenerationStrategy);

        this.players = new ArrayList<>(players);
        this.positions = new HashMap<>();
        players.forEach(player -> positions.put(player, STARTING_POSITION));
        this.winningState = winningState;
        this.snakes = new HashMap<>(snakes);
        this.ladders = new HashMap<>(ladders);
        this.moveGenerationStrategy = moveGenerationStrategy;
        // CHANGE: Explicitly setting the current player index to 0
        this.currentPlayerIndex = 0;
    }

    private void switchCurrentPlayer() {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
    }

    public void play() {
        // CHANGE: Improve user interaction
        System.out.println("=== Snake and ladder game started ===");
        System.out.println("Players: " + players.stream().map(Player::getName).reduce((a,b) -> a + ", " + b).orElse(""));
        System.out.println("Winning position: " + winningState);
        System.out.println("======= \n");

        int turnCount = 0; // UPDATED: Add turn counter for statistics

        while (true) {
            turnCount++;
            Player currentPlayer = players.get(currentPlayerIndex);
            int currentPosition = positions.get(currentPlayer);
            // CHANGE: Enhanced output to show turn number and current position
            System.out.println("Turn: " + turnCount + ": "+ currentPlayer.getName() + "'s turn (Current position: " + currentPosition + " )");

            int diceRoll = moveGenerationStrategy.next();
            System.out.println("Dice roll: " + diceRoll);

            int nextPosition = currentPosition + diceRoll;

            // CHANGED: next position should next exceed winning state (eg: if user at 99 and dice roll says 2, user cannot go to 101, instead they are asked to roll the dice again)
            if (nextPosition > winningState) {
                System.out.println("Cannot move! Roll exceeds winning position. Need exactly " + (winningState-currentPosition) + " to win");
                switchCurrentPlayer();
                System.out.println();
                continue;
            }

            // CHANGE: Show movement from current to next position
            System.out.println("Moving from " + currentPosition + " to " + nextPosition);

            // UPDATE: Add feedback for snake encounters

            if (snakes.containsKey(nextPosition)) {
                int snakeTail = snakes.get(nextPosition);
                System.out.println("Oh no! Snake bite, sliding down from" + nextPosition + " to " + snakeTail);
                nextPosition = snakeTail;
            }
            // CHANGE: Enhanced feedback for ladder encounters
            else if (ladders.containsKey(nextPosition)) {
                int ladderTop = ladders.get(nextPosition);
                System.out.println("Yay! Climbing ladder from " + nextPosition + " to " + ladderTop);
                nextPosition = ladderTop;
            }

            positions.put(currentPlayer, nextPosition);

            System.out.println("Final position: " + nextPosition);

            if (nextPosition == winningState) {
                    System.out.println("\n🎉🎉🎉 GAME OVER 🎉🎉🎉");
                    System.out.println("Congratulations " + currentPlayer.getName() +
                            "! You have won the game in " + turnCount + " turns!");
                    System.out.println("=====================================");
                    return;
            }
            switchCurrentPlayer();
            System.out.println(); // UPDATED: Added blank link for readability
        }
    }

    public Map <Player, Integer> getCurrentPositions() {
        return Collections.unmodifiableMap(positions);
    }
};

public class Main {
    public static void main(String [] args) {
        // Create players
        List<Player> players = Arrays.asList(
                new Player("1", "Alice"),
                new Player("2", "Bob")
        );

        // Define snakes (head -> tail)
        Map<Integer, Integer> snakes = new HashMap<>();
        snakes.put(17, 7);
        snakes.put(54, 34);
        snakes.put(62, 19);
        snakes.put(98, 79);

        // Define ladders (bottom -> top)
        Map<Integer, Integer> ladders = new HashMap<>();
        ladders.put(3, 22);
        ladders.put(5, 8);
        ladders.put(11, 26);
        ladders.put(20, 29);

        // Create game
        GameContext game = new GameContext(
                players,
                100, // winning position
                snakes,
                ladders,
                new DiceMoveGenerationStrategy()
        );

        // Play game
        game.play();


    }
};