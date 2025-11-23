// Implementing the solution as done by Shrayansh in: https://gitlab.com/shrayansh8/interviewcodingpractise/-/tree/main/src/main/java/com/conceptandcoding/LowLevelDesign/LLDCricbuzz
// This is a domain heavy problem, practise it again to improve proficiency

import java.util.*;

public class Main {


    public static void main(String [] args) {
        Team team1 = addTeam("India");
        Team team2 = addTeam("Sri Lanka");

        MatchType matchType = new T20MatchType();
        Match match = new Match(team1, team2, null, "SMS Stadium", matchType);
        match.startMatch();

    }

    private static Team addTeam(String name) {
        Queue <Player> players = new LinkedList<>();
        List <Player> bowlers = new ArrayList<>();

        for (int i=1; i<=11; i++) {
            Player player = createPlayer(name + String.valueOf(i), PlayerType.ALLROUNDER);
            if (i>=8) bowlers.add(player);
            players.add(player);
        }

        Team team = new Team(name, players, new ArrayList<>(), bowlers);
        return team;
    }

    private static Player createPlayer(String name, PlayerType playerType) {
        Person person = new Person();
        person.name = name;
        Player player = new Player(person, playerType);
        return player;
    }
}
