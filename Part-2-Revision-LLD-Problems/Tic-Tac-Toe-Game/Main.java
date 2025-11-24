import java.util.*;

enum Symbol {
    X, O, EMPTY
}

/* CHANGE: IMPORTANT : Extracted game status to enable engine-style result handling

Benefits

Single Responsibility:
    Controller: acquires input (scan from console/UI/API), validates/normalizes, maps to commands.
    Game: enforces rules/state transitions only.

Testability:
    Game logic is pure and deterministic—easy unit tests with no I/O.
    Controller can be tested with mocked input/output streams.

Swap front-ends:
    Replace CLI scanner with GUI, REST, WebSocket, or bot without touching game rules.
Error handling at the edges:
    Controller handles parsing, retries, UX messages; game throws on illegal moves—clean layering.
*/

enum GameStatus {
    IN_PROGRESS, WIN, DRAW
}

// UPDATED: Standardized move result for game engine
final class MoveResult {
    private final GameStatus status;
    private final String message;

    public MoveResult(GameStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public GameStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}

final class Board {
    private final Symbol [][] grid;
    private final int n;
    private final Map <Symbol, int[]> rowCounts;
    private final Map <Symbol, int[]> columnCounts;
    private final Map <Symbol, Integer> leftDiagonalCounts;
    private final Map <Symbol, Integer> rightDiagonalCounts;
    private int occupiedCells;

    public Board(int n) {
        if (n <= 1) throw new IllegalArgumentException("Board size has to be atleast 2");
        this.n = n;
        grid = new Symbol[n][n];
        for (int i=0;i<n;i++) Arrays.fill(grid[i], Symbol.EMPTY);

        // Use EnumMap over HashMap for performance reasons
        rowCounts = new EnumMap<>(Symbol.class);
        columnCounts = new EnumMap<>(Symbol.class);
        leftDiagonalCounts = new EnumMap<>(Symbol.class);
        rightDiagonalCounts = new EnumMap<>(Symbol.class);
        occupiedCells = 0;

        for (Symbol symbol: Symbol.values()) {
            rowCounts.put(symbol, new int[n]);
            columnCounts.put(symbol, new int[n]);
            leftDiagonalCounts.put(symbol, 0);
            rightDiagonalCounts.put(symbol, 0);
        }
    }

    // UPDATE: Use row, col instead of x,y for grid semantics
    public boolean isWithinBounds(int row, int col) {
        return row>=0 && col>=0 && row<n && col<n;
    }

    public boolean isEmpty(int row, int col) {
        if (!isWithinBounds(row,col)) throw new IllegalArgumentException("Given cell not within bounds");
        return grid[row][col] == Symbol.EMPTY;
    }

    private boolean isLeftDiagonal(int row, int col) {
        return row == col;
    }

    private boolean isRightDiagonal(int row, int col) {
        return (row + col) == n-1;
    }

    public void placeSymbol(int row, int col, Symbol symbol) {
        if (!isWithinBounds(row,col)) throw new IllegalArgumentException("Given cell not within bounds");
        if (!isEmpty(row,col)) throw new IllegalArgumentException("Given cell already occupied");
        if (symbol == Symbol.EMPTY) throw new IllegalArgumentException("Cannot place empty symbol");

        grid[row][col] = symbol;
        occupiedCells++;

        rowCounts.get(symbol)[row]++;
        columnCounts.get(symbol)[col]++;
        if (isLeftDiagonal(row,col)) leftDiagonalCounts.put(symbol, leftDiagonalCounts.get(symbol) + 1);
        if (isRightDiagonal(row,col)) rightDiagonalCounts.put(symbol, rightDiagonalCounts.get(symbol) + 1);
    }

    // UPDATE: Renamed to hasWinAt to reflect intent precisely
    public boolean hasWinAt(int row, int col, Symbol symbol) {
        return rowCounts.get(symbol)[row] == n || columnCounts.get(symbol)[col] == n || leftDiagonalCounts.get(symbol) == n || rightDiagonalCounts.get(symbol) == n;
    }

    // UPDATE: Clear, intention revealing name
    public boolean isFull() {
        return occupiedCells == (n*n);
    }

    // UPDATE: Useful for console rendering and debugging
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r=0; r<n; r++) {
            sb.append(System.lineSeparator());
            for (int c=0; c<n; c++) {
                Symbol s = grid[r][c];
                char ch = (s == Symbol.EMPTY) ? '.' : (s == Symbol.X ? 'X' : 'O');
                sb.append(' ');
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}

class Player {
    private final String id;
    private final Symbol symbol;

    public Player(String id, Symbol symbol) {
        if (id == null || symbol == null || symbol == Symbol.EMPTY) throw new IllegalArgumentException("Invalid parameters passed for player");
        this.id = id;
        this.symbol = symbol;
    }

    public Symbol getSymbol() {
        return symbol;
    }

    public String getId() {
        return id;
    }
}

// UPDATE: Extracted core rules into a testable engine
final class Game {
    private final Board board;
    private final List <Player> players;
    private int currentPlayerIndex;
    private GameStatus status;

    private void switchPlayer() {
        currentPlayerIndex^=1;
    }

    public Player getCurrentPlayer() {
        return players.get(currentPlayerIndex);
    }

    public Board getBoard() {
        return board;
    }

    public GameStatus getStatus() {
        return status;
    }

    public Game(Board board, List <Player> players) {
        if (board == null || players == null) throw new IllegalArgumentException("Board and players cannot be null");
        if (players.size() != 2) throw new IllegalArgumentException("Exactly 2 sized players list required");
        Player firstPlayer = players.get(0);
        Player secondPlayer = players.get(1);

        if (firstPlayer.getSymbol() == secondPlayer.getSymbol() || firstPlayer.getId().equals(secondPlayer.getId())) {
            throw new IllegalArgumentException("Players should have unique IDs and symbols");
        }

        this.currentPlayerIndex = 0;
        this.board = board;
        this.players = new ArrayList<>(players);
        this.status = GameStatus.IN_PROGRESS; // UPDATE: Set the game to 'in progress' initially
    }

    // Updated: Single step move that validates, updates state and returns outcome

    public MoveResult playTurn(int row, int col) {
        if (status != GameStatus.IN_PROGRESS) {
            return new MoveResult(status, "Game is already finished");
        }

        Player current = getCurrentPlayer();
        Symbol symbol = current.getSymbol();
        board.placeSymbol(row, col, symbol);

        if (board.hasWinAt(row, col, symbol)) {
            status = GameStatus.WIN;
            return new MoveResult(GameStatus.WIN, "Player "+ current.getId()+ " wins!");
        }

        if (board.isFull()) {
            status = GameStatus.DRAW;
            return new MoveResult(GameStatus.DRAW, "Game ended in draw");
        }

        switchPlayer();
        return new MoveResult(GameStatus.IN_PROGRESS, "Move accepted, next players' turn");
    }
}

// UPDATED: UX Layer which internally talks to Game engine
class GameController {
    private final Game game;
    private final Scanner scanner;

    public GameController(Game game) {
        this.game = game;
        scanner = new Scanner(System.in);
    }

    private int inputInteger(String prompt) {
        // UPDATED: Read integers from full lines to avoid Scanner nextInt() pitfalls
        while (true) {
            System.out.println(prompt);
            String line = scanner.nextLine();
            try {
                return Integer.parseInt(line.trim());
            }
            catch (NumberFormatException ex) {
                System.out.println("Invalid input, please enter a whole number");
            }
        }
    }

    public void run() {
        try {
            while (game.getStatus() == GameStatus.IN_PROGRESS) {
                Player current = game.getCurrentPlayer();
                System.out.println();
                System.out.println("Current board: ");
                System.out.println(game.getBoard());
                System.out.println();
                System.out.println("Player: "+ current.getId() + " please enter your move");
                int row = inputInteger("Enter row");
                int col = inputInteger("Enter column");

                try {
                    MoveResult result = game.playTurn(row, col);
                    System.out.println(result.getMessage());
                }
                catch (IllegalArgumentException ex) {
                    // UPDATE: Catch invalid moves and continue
                    System.out.println("Invalid move: " + ex.getMessage());
                }
            }
            // Print final board and outcome
            System.out.println();
            System.out.println("Final board: ");
            System.out.println(game.getBoard());
            System.out.println("Game status: " + game.getStatus());
        }
        finally {
            // Good practice to always clouse resources in finally
            scanner.close();
        }
    }
}

public class Main {
    public static void main(String [] args) {
        Board board = new Board(3);
        Player p1 = new Player("P1", Symbol.X);
        Player p2 = new Player("P2", Symbol.O);
        Game game = new Game(board, Arrays.asList(p1, p2));
        new GameController(game).run();
    }
}