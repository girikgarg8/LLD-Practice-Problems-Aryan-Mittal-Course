import java.util.*;

record User(String id) {

};

class ExpenseWeight {
    private final User user;
    private final double percentage;

    public ExpenseWeight(User user, double percentage) {
        this.user = user;
        this.percentage = percentage;
    }

    public double getPercentage() {
        return percentage;
    }
};

class ExpenseShare {
    private final User user;
    private final double amount;

    public ExpenseShare(User user, double amount) {
        this.user = user;
        this.amount = amount;
    }

};

class Expense {
    // TODO: Client responibility to ensure that no 'expense share' recorded against payer?
    private User payer;
    private List <ExpenseShare> expenseShares;

    public Expense(User payer, List <ExpenseShare> expenseShares) {
        this.payer = payer;
        this.expenseShares = expenseShares;
    }

};

class Group {
    private final String id;
    private List <User> members;
    private List <Expense> expenseTransactions;

    public boolean areParticipantsMemberOfGroup(List <User> users) {
        return members.containsAll(users);
    }

    public void recordExpense(User payer, List <User> owers, double amount, SplitStrategy splitStrategy) {
        if (!areParticipantsMemberOfGroup(List.of(payer, owers))) {
            throw new IllegalStateException("One or more members not participants in the group")
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount has to positive");
        }
        List <ExpenseShare> expenseShares = splitStrategy.calculate(payer, owers, amount);
    }
};

interface SplitStrategy {
    public List <ExpenseShare> calculate(List <User> owers, double amount);
};

class EvenSplitStrategy implements SplitStrategy {
    @Override
    public List <ExpenseShare> calculate(List <User> owers, double amount) {
        double individualShare = amount/(owers.size());
        return owers.stream().map((User ower) -> new ExpenseShare(ower, individualShare)).collect(Collectors.toList());
    }
};

class WeighedSplitStrategy implements SplitStrategy {
    private List <ExpenseWeight> expenseWeights;

    private boolean validateWeights(List <ExpenseWeight> expenseWeights) {
        return expenseWeights.stream().mapToDouble(expenseWeight -> expenseWeight.getPercentage().doubleValue()).sum() == (double) 100;
    }

    public WeightedStrategy(List <ExpenseWeight> expenseWeights) {
        if (!validateWeights(expenseWeights)) {
            throw new IllegalArgumentException("Sum of weights needs to be 100");
        }
        this.expenseWeights = expenseWeights;
    }

    private boolean validateOwers(List <User> owers) {
        Set <User> 
    }

    @Override
    public List <ExpenseShare> calculate(List <User> owers, double amount) {
        if (!validateOwers(owers)) {
            throw new IllegalStateException("One or more owers do not have a weight associated with them");
        }


    }
};

public class Main {
    public static void main(String [] args) {


    }
};