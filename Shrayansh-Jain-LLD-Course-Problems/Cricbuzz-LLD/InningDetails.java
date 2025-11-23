import java.util.*;

public class InningDetails {
    Team battingTeam;
    Team bowlingTeam;
    MatchType matchType;
    List <OverDetails> overs;

    public InningDetails(Team battingTeam, Team bowlingTeam, MatchType matchType) {
        this.battingTeam = battingTeam;
        this.bowlingTeam = bowlingTeam;
        this.matchType = matchType;
        this.overs = new ArrayList<>();
    }

    public void start(int runsToWin) {
        // set batting players
        try {
            battingTeam.chooseNextBatsman();
        }
        catch (Exception ex) {

        }

        int noOfOvers = matchType.noOfOvers();
        for (int overNumber = 1; overNumber <= noOfOvers; overNumber++) {
            // choose bowler
            bowlingTeam.chooseNextBowler(matchType.maxOverCountBowlers());

            OverDetails over = new OverDetails(overNumber, bowlingTeam.getCurrentBowler());
            overs.add(over);
            try {
                boolean won = over.startOver(battingTeam, bowlingTeam, runsToWin);
                if (won == true) break;
            }
            catch (Exception ex) {
                break;
            }

            // swap striker and non-striker
            Player temp = battingTeam.getStriker();
            battingTeam.setStriker(battingTeam.getNonStriker());
            battingTeam.setNonStriker(temp);
        }
    }

    public int getTotalRuns() {
        return battingTeam.getTotalRuns();
    }
};


