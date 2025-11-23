import java.util.*;

public class Player {
    public Person person;
    public PlayerType playerType;
    public BattingScoreCard battingScoreCard;
    public BowlingScoreCard bowlingScoreCard;

    public Player(Person person, PlayerType playerType) {
        this.person = person;
        this.playerType = playerType;
        battingScoreCard = new BattingScoreCard();
        bowlingScoreCard = new BowlingScoreCard();
    }

    public void printBattingScoreCard() {
        System.out.println("Player name: " + person.name + " -- totalRuns: " + battingScoreCard.totalRuns + " -- totalBalls Played: " + battingScoreCard.totalBallsPlayed + " -- 4s: "+ battingScoreCard.totalFours + " --6s:" + battingScoreCard.totalSix + " -- outBy" + ((battingScoreCard.wicketDetails != null) ? battingScoreCard.wicketDetails.takenBy.person.name : "notout"));
    }

    public void printBowlingScoreCard() {
        System.out.println("Player name: " + person.name + " -- totalOversThrown: " + bowlingScoreCard.totalOversCount + " -- total runs given: " + bowlingScoreCard.runsGiven + " -- wickets Taken: "+ bowlingScoreCard.wicketsTaken);
    }
};