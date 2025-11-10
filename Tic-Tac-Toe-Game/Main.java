import java.util.*;

enum Symbol {
    X, O, EMPTY
};

// CHANGE: Track counts to make win detection O(1)
class Board {
    private final Symbol [][] matrix;
    private final int n;
    private int numEmptySlots;

    // maintain count of O's and X's in each row, column and diagonal. This allows us to check for win in the board in O(1) time without having to traverse the board
    private final EnumMap <Symbol, int []> rowCounts = new EnumMap<>(Symbol.class); // EnumMap[X][1] represents number of X's present in the 1th row of the board
    private final EnumMap <Symbol, int[]> columnCounts = new EnumMap<>(Symbol.class);
    private final EnumMap <Symbol, Integer> leftDiagonalCounts = new EnumMap<>(Symbol.class);
    private final EnumMap <Symbol, Integer> rightDiagonalCounts = new EnumMap<>(Symbol.class);


    public Board (int n) {
        if (n<=0) throw new IllegalArgumentException("Board size must be positive");
        this.n = n;
        matrix = new Symbol[n][n];
        numEmptySlots = n*n;

        for (int i=0;i<n;i++) {
            Arrays.fill(matrix[i], Symbol.EMPTY);
        }

        for (Symbol s: Symbol.values()) {
            if (s == Symbol.EMPTY) continue; // changed
            rowCounts.put(s, new int[n]);
            columnCounts.put(s, new int[n]);
            leftDiagonalCounts.put(s, 0);
            rightDiagonalCounts.put(s, 0);
        }

    }

    private boolean withinBounds(int x, int y) {
        return x>=0 && x<n && y>=0 && y<n;
    }

    public boolean isMoveValid(int x, int y) {
        if (!withinBounds(x,y)) {
            System.out.println("Input coordinates not within the bounds of board");
            return false;
        }
        if (matrix[x][y] != Symbol.EMPTY) {
            System.out.println("Input cell is occupied already");
            return false;
        }
        return true;
    }

    // CHANGE: Strongly-typed move API + internal validation
    public boolean makeMove(Symbol symbol, int x, int y) {
        if (!isMoveValid(x,y)) {
            return false;
        }
        matrix[x][y] = symbol;
        numEmptySlots--;
        System.out.println("Successfully placed "+ symbol.name() + " at x: "+ x + " and y: "+ y);
        rowCounts.get(symbol)[x]++;
        columnCounts.get(symbol)[y]++;
        if (x==y) leftDiagonalCounts.put(symbol, leftDiagonalCounts.get(symbol) + 1);
        if ((x+y)==n-1) rightDiagonalCounts.put(symbol, rightDiagonalCounts.get(symbol)+1);
        return true;
    }

//    private boolean checkHorizontalWin(int x, int y) {
//        Symbol currentCellSymbol = matrix[x][y];
//        for (int i=0;i<n;i++) {
//            if (matrix[x][i] != currentCellSymbol) return false;
//        }
//        return true;
//    }
//
//    private boolean checkVerticalWin(int x, int y) {
//        Symbol currentCellSymbol = matrix[x][y];
//        for (int i=0;i<n;i++) {
//            if (matrix[i][y] != currentCellSymbol) return false;
//        }
//        return true;
//    }
//
//    private boolean checkLeftDiagonalWin(int x, int y) {
//        if (x!=y) return false;
//        Symbol currentCellSymbol = matrix[x][y];
//        int xIterator = 0;
//        int yIterator = 0;
//        while (xIterator<n && yIterator<n) {
//            if (matrix[xIterator][yIterator]!=currentCellSymbol) return false;
//            xIterator++;
//            yIterator++;
//        }
//        return true;
//    }
//
//    private boolean checkRightDiagonalWin(int x, int y) {
//        if ((x+y)!=n-1) return false;
//        Symbol currentCellSymbol = matrix[x][y];
//        int xIterator = 0;
//        int yIterator = n-1;
//        while (xIterator<n && yIterator>=0) {
//            if (matrix[xIterator][yIterator]!=currentCellSymbol) return false;
//            xIterator++;
//            yIterator--;
//        }
//        return true;
//    }

    public boolean checkWin(int x, int y) {
//        boolean isHorizontalWin = checkHorizontalWin(x,y);
//        boolean isVerticalWin = checkVerticalWin(x,y);
//        boolean isLeftDiagonalWin = checkLeftDiagonalWin(x,y);
//        boolean isRightDiagonalWin = checkRightDiagonalWin(x,y);
//        return isHorizontalWin || isVerticalWin || isLeftDiagonalWin || isRightDiagonalWin;

        Symbol symbol = matrix[x][y];
        return (rowCounts.get(symbol)[x] == n) || (columnCounts.get(symbol)[y] == n) || (leftDiagonalCounts.get(symbol) == n) || (rightDiagonalCounts.get(symbol) == n);
    }

    public boolean checkTie() {
        return numEmptySlots == 0;
    }

    public void print() {
        System.out.println();
        for (int i=0;i<n;i++) {
            for (int j=0;j<n;j++) {
                System.out.print(matrix[i][j].name() + " ");
            }
            System.out.println();
        }
    }
};

// CHANGE: Use a record with primitives for coordinates (simpler, immutable)
record Pair (int x, int y) {

};

// CHANGE: Collapse PlayerIdentifier into Symbol and expose Symbol strongly
class Player {
    private final String id;
    private final Symbol symbol;

    public Player(String id, Symbol symbol) {
        if (symbol == Symbol.EMPTY)  {
            throw new IllegalArgumentException("Player cannot use EMPTY state");
        }
        this.id = id;
        this.symbol = symbol;
    }

    public String getId() {
        return id;
    }

    public Symbol getSymbol() {
        return symbol;
    }
};

class ScannerSingleton {
    private static volatile Scanner instance;

    public static Scanner getInstance() {
        if (instance == null) {
            synchronized(ScannerSingleton.class) {
                if (instance == null) instance = new Scanner(System.in);
            }
        }
        return instance;
    }
};

// CHANGE: Use index toggle; delegate messages to GameContext (no prints in Board)
class GameContext {
    private final Board board;
    private final List<Player> players;
    private int currentIndex = 0;

    private void alternateCurrentPlayer() {
       currentIndex ^= 1; // toggles 0<->1; 0^1 = 1, 1^1 = 0
    }

    private void validatePlayersList(List <Player> players) {
        if (players == null || players.size()<2) throw new IllegalArgumentException("Players list passed with invalid size");
        String firstPlayerId = players.get(0).getId();
        String secondPlayerId = players.get(1).getId();
        Symbol firstPlayerSymbol = players.get(0).getSymbol();
        Symbol secondPlayerSymbol = players.get(1).getSymbol();
        if ((firstPlayerId.equals(secondPlayerId))|| (firstPlayerSymbol.equals(secondPlayerSymbol))) throw new IllegalArgumentException("Players must have unique id and symbol");
    }

    public GameContext(Board board, List <Player> players) {
        validatePlayersList(players);
        this.board = board;
        this.players = List.copyOf(players);
    }

    private Integer inputInteger() {
        Integer output = -1;
        boolean validInput = false;
        while (!validInput) {
            try {
                output = ScannerSingleton.getInstance().nextInt();
                validInput = true;
            }
            catch (InputMismatchException ex) {
                System.out.println("Invalid input received, please try again");
                ScannerSingleton.getInstance().next(); // Consume the invalid input to prevent an infinite loop
            }
        }
        return output;
    }

    private Pair inputCoordinates() {
        Integer x = -1;
        Integer y = -1;
        System.out.println("Please enter x coordinate");
        x = inputInteger();
        System.out.println("Please enter y coordinate");
        y = inputInteger();
        return new Pair(x,y);
    }

    public void run() {
        while (true) {
            board.print();
            Player currentPlayer = players.get(currentIndex);
            String currentPlayerId = currentPlayer.getId();
            Symbol currentPlayerSymbol = currentPlayer.getSymbol();
            System.out.println("Player: " + currentPlayerId + " please make your move: ");
            Pair coordinates = inputCoordinates();
            int x = coordinates.x();
            int y = coordinates.y();

            if (!board.makeMove(currentPlayerSymbol, x, y)) {
                continue;
            };

            if (board.checkWin(x,y)) {
                System.out.println("Congrats, player with ID: " + currentPlayerId + " has won");
                break;
            }
            else if (board.checkTie()) {
                System.out.println("Game has tied");
                break;
            }
            alternateCurrentPlayer();
        }
        ScannerSingleton.getInstance().close();
    }
};

public class Main {
    public static void main(String [] args) {
        Board board = new Board(3);
        Player girik = new Player("Girik", Symbol.X);
        Player nikhil = new Player("Nikhil", Symbol.O);
        GameContext gameContext = new GameContext(board, List.of(girik, nikhil));
        gameContext.run();
    }
}