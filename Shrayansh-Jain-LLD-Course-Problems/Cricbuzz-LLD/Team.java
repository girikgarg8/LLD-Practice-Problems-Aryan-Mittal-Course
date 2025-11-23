import java.util.*;

public class Team {
    public String teamName;
    public Queue <Player> playing11;
    public List <Player> bench;
    public PlayerBattingController battingController;
    public PlayerBowlingController bowlingController;
    public boolean isWinner;

    public Team(String teamName, Queue <Player> playing11, List <Player> bench, List <Player> bowlers) {
        this.teamName = teamName;
        this.playing11 = playing11;
        this.bench = bench;
        this.battingController = new PlayerBattingController(playing11);
        this.bowlingController = new PlayerBowlingController(bowlers);
    }

    public String getTeamName() {
        return teamName;
    }

    public void chooseNextBatsman() throws Exception {
        battingController.getNextPlayer();
    }

    public void chooseNextBowler(int maxOverCountPerBowler) {
        bowlingController.getNextBowler(maxOverCountPerBowler);
    }

    public Player getStriker() {
        return battingController.getStriker();
    }

    public Player getNonStriker() {
        return battingController.getNonStriker();
    }

    public void setStriker(Player player) {
        battingController.setStriker(player);
    }

    public void setNonStriker(Player player) {
        battingController.setNonStriker(player);
    }

    public Player getCurrentBowler() {
        return bowlingController.getCurrentBowler();
    }

    public void printBattingScoreCard() {
        for (Player player: playing11) {
            player.printBattingScoreCard();
        }
    }

    public void printBowlingScoreCard() {
        for (Player player: playing11) {
            if (player.bowlingScoreCard.totalOversCount > 0) {
                player.printBowlingScoreCard();
            }
        }
    }

    public int getTotalRuns() {
        int totalRuns = 0;
        for (Player player: playing11) {
            totalRuns += player.battingScoreCard.totalRuns;
        }
        return totalRuns;
    }
};