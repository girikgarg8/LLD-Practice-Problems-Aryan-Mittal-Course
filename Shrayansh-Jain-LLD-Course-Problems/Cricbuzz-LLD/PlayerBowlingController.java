import java.util.*;

public class PlayerBowlingController {
    Deque <Player> bowlersList;
    Map <Player, Integer> bowlerVsOverCount;
    Player currentBowler;

    public PlayerBowlingController(List <Player> bowlersList) {
        this.bowlersList = new LinkedList<>();
        this.bowlerVsOverCount = new HashMap<>();
        for (Player bowler: bowlersList) {
            this.bowlersList.addLast(bowler);
            bowlerVsOverCount.put(bowler, 0);
        }
    }

    public void getNextBowler(int maxOverCountPerBolwer) {
        Player player = bowlersList.poll();
        if (bowlerVsOverCount.get(player) + 1 == maxOverCountPerBolwer) {
            currentBowler = player;
        }
        else {
            currentBowler = player;
            bowlersList.addLast(player);
            bowlerVsOverCount.put(player, bowlerVsOverCount.get(player)+1);
        }
    }

    public Player getCurrentBowler() {
        return currentBowler;
    }
};