import java.util.*;
import java.util.stream.*;

record User(String id) {
    @Override
    public String toString() {
        return id;
    }
};

class ExpenseWeight {
    private final User user;
    private final double percentage;

    // CHANGE: Add validation to ensure that all of the individual weights has to be between 0-100
    public ExpenseWeight(User user, double percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("Percentage must be between 0 and 100");
        }
        this.user = user;
        this.percentage = percentage;
    }

    public double getPercentage() {
        return percentage;
    }

    public User getUser() {
        return user;
    }
};

class ExpenseShare {
    private final User user;
    private final double amount;

    // CHANGE: Add validation for non-negative amount
    public ExpenseShare(User user, double amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount cannot be negative");
        this.user = user;
        this.amount = amount;
    }

    // CHANGE: Add missing getters
    public User getUser() {
        return user;
    }

    public double getAmount() {
        return amount;
    }
};

class Expense {
    // CHANGE: immutability of fields, unless we assume usecases where these fields need to change, let's delcare the fields final
    private final User payer;
    private final List <ExpenseShare> expenseShares;
    private final double totalAmount;

    public Expense(User payer, List <ExpenseShare> expenseShares) {
        this.payer = payer;
        // CHANGE: Defensive copy, while setting or getting the list to clients, always create/return a copy
        this.expenseShares = new ArrayList<>(expenseShares);
        // CHANGE: Calculate total amount for reference
        this.totalAmount = expenseShares.stream().mapToDouble(ExpenseShare::getAmount).sum();
    }

    public User getPayer() {
        return payer;
    }

    public List <ExpenseShare> getExpenseShares() {
        return List.copyOf(expenseShares);
    }

    public double getTotalAmount() {
        return totalAmount;
    }
};

// Requirement: The system provides a simplified settlement plan with minimal transactions.
// CHANGE: New class added, it represents a 'simpified transaction' (Transaction which sould be done so that all the debts within a group can be settled in minimal operations)
class SettlementTransaction {
    private final User from;
    private final User to;
    private final double amount;

    public SettlementTransaction(User from, User to, double amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount cannot be negative");
        this.amount = amount;
        this.from = from;
        this.to = to;
    }

    public double getAmount() {
        return amount;
    }

    @Override
    public String toString() {
        return String.format("%s pays %s: $%.2f", from, to, amount);
    }
};

// CHANGE: New class 'BalanceEntry' used for encapsulating both the user and their pending balance. This is used in the priority queue based solution to calculate minimal settlement strategy

class BalanceEntry {
    private final User user;
    // Not making it final since the same entry will be popped out, and updated with the new 'remaining balance'
    private double balance;

    public BalanceEntry(User user, double balance) {
        // CHANGE: Remove validation of negative balance. For debitors, we will be using negative balance
        this.user = user;
        this.balance = balance;
    }

    public User getUser() {
        return user;
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }
};

class Group {
    private final String id;
    // CHANGE: Added immutability for field
    private final List <User> members;
    // CHANGE: Added immutability for field
    private final List <Expense> expenseTransactions;

    // CHANGE: Collections in Java can only hold Non-primitive data types -> change from double to Double
    // Key is who owes, value is Map of (to whom -> how much)
    private final Map <User, Map<User, Double>> balanceSheet;

    private final float PRECISION_TOLERANCE = 0.01f;

    // CHANGE: Add constructor with proper initialization of fields, also add required validations
    public Group(String id, List <User> members) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("Members cannot be empty");
        }
        this.id = id;
        this.members = new ArrayList<>(members);
        this.expenseTransactions = new ArrayList<>();
        this.balanceSheet = new HashMap<>();

        // CHANGE: Add initialization of hashmap for each group member
        for (User member: members) {
            balanceSheet.put(member, new HashMap<>());
        }
    }

    public String getId() {
        return id;
    }

    /*
        CHANGE: Modified the logic for performance optimization
        members.containsAll(users)
        Internally, it iterates over the users list and then checks if is part of members list
        TC: O(M*N) where M = Users.size and N = Members.size

        However, new HashSet<>(members).containsAll(users);
        will iterate over the users list and then check the membership in members set
        Lookup in set is O(1)
        TC: O(M*1) where M = users.size
     */
    public boolean areParticipantsMemberOfGroup(List <User> users) {
        return new HashSet<>(members).containsAll(users);
    }

    public void recordExpense(User payer, List <User> owers, double amount, SplitStrategy splitStrategy) {
        // CHANGE: List.of(payer, owers) will not work since payer is of the type User and owers is of the type List<User>
        List <User> allParticipants = new ArrayList<>(owers);
        allParticipants.add(payer);

        if (!areParticipantsMemberOfGroup(allParticipants)) {
            throw new IllegalStateException("One or more members not participants in the group");
        }

        if (amount <= 0) {
            throw new IllegalArgumentException("Amount has to positive");
        }

        // CHANGE: Add validation to ensure that payer is not included in the owers list
        if (owers.contains(payer)) {
            throw new IllegalArgumentException("Payer cannot be in the list of owers");
        }

        List <ExpenseShare> expenseShares = splitStrategy.calculate(payer, owers, amount);

        double totalShares = expenseShares.stream().mapToDouble(ExpenseShare::getAmount).sum();

        /*
        CHANGE: Validate that expense shares sum matches amount (with small tolerance for floating point)

         Reason this is required:
            Problem: splitStrategy could be ANY implementation of the interface:
            Built-in: EvenSplitStrategy, WeighedSplitStrategy
            Custom/User-defined: Someone could create a buggy implementation
            Malicious: Someone could try to game the system
            You're calling a method on an interface where you don't control what happens inside!
         */

        if (Math.abs(totalShares-amount) > PRECISION_TOLERANCE) {
            throw new IllegalStateException("Expense shares don't sum to total amount");
        }

        /*
        CHANGE: Store the expense in expenseTransactions
        expenseTransactions will preserve the original list of transactions
        The peer to peer transactions (A,B) and the optimal list of settlements are being managed differently
        */

        Expense expense = new Expense(payer, expenseShares);
        expenseTransactions.add(expense);

        for (ExpenseShare share: expenseShares) {
            User ower = share.getUser();
            double shareAmount = share.getAmount();
            updateBalance(ower, payer, shareAmount);
        }
    }

    // CHANGE: New helper method for cleaner balance updates
    private void updateBalance(User debtor, User creditor, double amount) {
        Map <User, Double> debtorBalances = balanceSheet.get(debtor);
        Map <User, Double> creditorBalances = balanceSheet.get(creditor);

        double currentDebt = debtorBalances.getOrDefault(creditor, 0.0);
        double currentCredit = creditorBalances.getOrDefault(debtor, 0.0);

        double netAmount = currentDebt + amount - currentCredit;
        if (netAmount > 0) {
            debtorBalances.put(creditor, netAmount);
            creditorBalances.put(debtor, 0.0);
        }
        else if (netAmount < 0) {
            creditorBalances.put(debtor, -netAmount);
            debtorBalances.put(creditor, 0.0);
        }
        else {
            creditorBalances.put(debtor, 0.0);
            debtorBalances.put(creditor, 0.0);
        }
    }

    // Change: Fixed iteration and formatting
    public void printRequiredSettlement(User user) {
        if (!balanceSheet.containsKey(user)) {
            throw new IllegalArgumentException("User is not a member of this group");
        }

        System.out.println("\n=== Settlement for " + user + " ===");

        Map <User, Double> userBalances = balanceSheet.get(user);
        boolean hasSettlements = false;

        for (Map.Entry <User, Double> entry: userBalances.entrySet()) {
            User otherUser = entry.getKey();
            double amount = entry.getValue();

            if (amount > PRECISION_TOLERANCE) {
                System.out.printf("%s owes %s: %.2f %n", user, otherUser,amount);
                hasSettlements = true;
            }
        }

        // CHANGE: Also show who owes this user
        for (User member: members) {
            if (!member.equals(user)) {
                double amountOwedToUser = balanceSheet.get(member).getOrDefault(user, 0.0);
                if (amountOwedToUser > PRECISION_TOLERANCE) {
                    System.out.printf("%s owes %s: %.2f %n", member, user, amountOwedToUser);
                    hasSettlements = true;
                }
            }
        }

        if (!hasSettlements) {
            System.out.println("All settled!");
        }
    }

    // ============= NEW: SIMPLIFIED SETTLEMENT WITH PRIORITY QUEUE =============

    /**
     * NEW: Calculate net balance for each user
     * Positive balance = user is owed money (creditor)
     * Negative balance = user owes money (debtor)
     *
     * This method will only be called once during initialization of priority queue. Later the elements from priority queue will be dequeued, and updated with the new value and pushed back
     */

    private Map <User, Double> calculateNetBalance() {
        Map <User, Double> netBalances = new HashMap<>();

        // Initialize all members with 0 balance
        for (User member: members) {
            netBalances.put(member, 0.0);
        }

        // Calculate net balance for each user
        for (User user: members) {
            Map <User, Double> userDebts = balanceSheet.get(user);

            for (Map.Entry <User, Double> entry: userDebts.entrySet()) {
                // Add amount user owes (negative contribution)
                double amount = entry.getValue();
                if (amount > PRECISION_TOLERANCE) {
                    netBalances.put(user, netBalances.get(user) - amount);
                }
            }

            // Add amounts owed to this user (positive contribution)
            for (User otherUser: members) {
                if (!otherUser.equals(user)) {
                    double amountOwed = balanceSheet.get(otherUser).getOrDefault(user, 0.0);
                    if (amountOwed > PRECISION_TOLERANCE) {
                        netBalances.put(user, netBalances.get(user) + amountOwed);
                    }
                }
            }
        }
        return netBalances;
    }

    /**
     * NEW: Generate simplified settlement plan using Priority Queue approach
     * This minimizes the number of transactions needed to settle all debts
     *
     * Algorithm:
     * 1. Calculate net balance for each user
     * 2. Create max heap for creditors (positive balances)
     * 3. Create max heap for debtors (negative balances, by absolute value)
     * 4. Repeatedly match largest creditor with largest debtor
     *
     * Time Complexity: O(n log n) where n is number of users
     * Space Complexity: O(n)
     */
     public List <SettlementTransaction> getSimplifiedStatement() {
         List <SettlementTransaction> settlements = new ArrayList<>();

         // Step-1 : Calculate net balances
         Map <User, Double> netBalances = calculateNetBalance();

         // Step-2 : Create priority queues
         // Max heap for creditors (people who are owed money)
         PriorityQueue <BalanceEntry> creditors = new PriorityQueue<>((a,b) -> Double.compare(b.getBalance(), a.getBalance()));

         // Max heap (by absolute value) for debitors (people who owe money)
         PriorityQueue <BalanceEntry> debitors = new PriorityQueue<>((a,b) -> Double.compare(Math.abs(b.getBalance()), Math.abs(a.getBalance())));

         // Step-3: Populate the heaps
         for (Map.Entry <User,Double> entry: netBalances.entrySet()) {
             double balance = entry.getValue();
             if (balance > PRECISION_TOLERANCE) {
                 // Creditor
                 creditors.add(new BalanceEntry(entry.getKey(), balance));
             }
             else if (balance < -PRECISION_TOLERANCE) {
                // Debitor, keep in mind the values we are inserting into pq is negative : eg -20
                 debitors.add(new BalanceEntry(entry.getKey(), balance));
             }
             // Skip users with ~0 balance
         }

         // Step-4: Match creditors with debitors
         while (!creditors.isEmpty() && !debitors.isEmpty()) {
             BalanceEntry creditor = creditors.poll();
             BalanceEntry debitor = debitors.poll();

             double creditAmount = creditor.getBalance();
             double debitAmount = Math.abs(debitor.getBalance());

             // Settle the minimum of credit and debt
             double settlementAmount = Math.min(creditAmount, debitAmount);

             // Create settlement transaction
            settlements.add(new SettlementTransaction(debitor.getUser(), creditor.getUser(), settlementAmount));

            // Update remaining balances
            double remainingCredit = creditAmount - settlementAmount;
            double remainingDebit = debitAmount - settlementAmount;

            if (remainingCredit > PRECISION_TOLERANCE) {
                creditor.setBalance(remainingCredit);
                creditors.add(creditor);
            }

            if (remainingDebit > PRECISION_TOLERANCE) {
                debitor.setBalance(-remainingDebit);
                debitors.add(debitor);
            }
         }
         return settlements;
     }

     // CHANGE: Print simplified settlement plan
     public void printSettlementPlan() {
         System.out.println("\n == Simplified settlement plan ==");
         System.out.println("Minimum number of transactions to settle all debts");

         List <SettlementTransaction> settlementTransactions = getSimplifiedStatement();
         if (settlementTransactions.isEmpty()) {
             System.out.println("All members are settled! No transactions needed");
             return ;
         }

         System.out.println("Total transactions needed: " + settlementTransactions.size());
         System.out.println();

         for (int i=0; i<settlementTransactions.size(); i++) {
             System.out.println((i+1) + " ." + settlementTransactions.get(i));
         }

         System.out.println();
     }

     // CHANGE: Add a method to compare the original expense transactions vs simplified settlement
    public void printSettlementComparison() {
        System.out.println("\n=== Settlement Complexity Comparison ===");

        int originalTransactions = 0;
        for (User user: members) {
            Map <User, Double> userBalances = balanceSheet.get(user);
            for (double amount: userBalances.values()) {
                if (amount > PRECISION_TOLERANCE) {
                    originalTransactions++;
                }
            }
        }

        List <SettlementTransaction> simplified = getSimplifiedStatement();
        int simplifiedTransactionsSize = simplified.size();
        System.out.println("Original approach: " + originalTransactions+ " transactions");
        System.out.println("Simplified approach: " + simplifiedTransactionsSize+ " transactions");
    }
};

// CHANGE: Add payer as method signature to the split method
// The payer parameter might seem redundant at first since the basic implementations don't use it, but it's included for extensibility and future-proofing.
interface SplitStrategy {
    public List <ExpenseShare> calculate(User payer, List <User> owers, double amount);
};

class EvenSplitStrategy implements SplitStrategy {
    @Override
    public List <ExpenseShare> calculate(User payer, List <User> owers, double amount) {
        // CHANGE: Add validation for empty owers list
        if (owers == null || owers.isEmpty()) {
            throw new IllegalArgumentException("Must have at least one ower");
        }
        double individualShare = amount/owers.size();
        return owers.stream().map(ower -> new ExpenseShare(ower, individualShare)).collect(Collectors.toList());
    }
};

class WeighedSplitStrategy implements SplitStrategy {
    // CHANGE: Mark immutable
    private final List <ExpenseWeight> expenseWeights;
    private final double PRECISION_TOLERANCE = 0.01d;

    // CHANGE: Add tolerance for floating point
    private boolean validateWeights(List <ExpenseWeight> expenseWeights) {
        double sum = expenseWeights.stream().mapToDouble(ExpenseWeight::getPercentage).sum();
        return Math.abs(sum-100.0) < PRECISION_TOLERANCE;
    }

    public WeighedSplitStrategy(List <ExpenseWeight> expenseWeights) {
        // CHANGE: Add check for empty expense weights
        if (expenseWeights == null || expenseWeights.isEmpty()) {
            throw new IllegalArgumentException("Must provide expense weights");
        }

        if (!validateWeights(expenseWeights)) {
            throw new IllegalArgumentException("Sum of weights needs to be 100");
        }
        // Change: Defensive copy
        this.expenseWeights = new ArrayList<>(expenseWeights);
    }

    private boolean validateOwersExactMatch(List <User> owers) {
        Set <User> weightUsers = expenseWeights.stream().map(ExpenseWeight::getUser).collect(Collectors.toSet());
        Set <User> owersSet = new HashSet<>(owers);
        return weightUsers.equals(owersSet);
    }

    @Override
    public List <ExpenseShare> calculate(User payer, List <User> owers, double amount) {
        if (owers == null || owers.isEmpty()) {
            throw new IllegalArgumentException("Must have at least one ower");
        }
        if (!validateOwersExactMatch(owers)) {
            throw new IllegalStateException("One or more owers do not have a weight associated with them");
        }
        return expenseWeights.stream()
                .map(expenseWeight -> new ExpenseShare(expenseWeight.getUser(), (expenseWeight.getPercentage()/100.0) * amount))
                .collect(Collectors.toList());
    }
};

// CHANGE: Add Exact Split Strategy also as mentioned in the problem statement
class ExactSplitStrategy implements SplitStrategy {
    private final Map <User, Double> exactAmounts;

    public ExactSplitStrategy(Map <User, Double> exactAmounts) {
        // Defensive copy
        this.exactAmounts = new HashMap<>(exactAmounts);
    }

    private boolean validateOwersExactMatch(List<User> owers) {
        Set<User> amountUsers = exactAmounts.keySet();
        Set<User> owersSet = new HashSet<>(owers);
        return amountUsers.equals(owersSet);
    }

    @Override
    public List<ExpenseShare> calculate(User payer, List<User> owers, double amount) {

        if (owers == null || owers.isEmpty()) {
            throw new IllegalArgumentException("Must have at least one ower");
        }

        if (!validateOwersExactMatch(owers)) {
            throw new IllegalStateException(
                    "Owers must exactly match users with assigned exact amounts"
            );
        }

        double totalSpecified = exactAmounts.values().stream().mapToDouble(Double::doubleValue).sum();

        if (Math.abs(totalSpecified - amount) > 0.01) {
            throw new IllegalArgumentException("Exact amounts don't sum to total amount");
        }

        return owers.stream()
                .map(ower -> new ExpenseShare(ower, exactAmounts.getOrDefault(ower,0.0)))
                .collect(Collectors.toList());
    }
};


public class Main {
    public static void main(String [] args) {
        // Create users
        User alice = new User("Alice");
        User bob = new User("Bob");
        User charlie = new User("Charlie");
        User diana = new User("Diana");

        // Create a group
        Group tripGroup = new Group("Trip2024", Arrays.asList(alice, bob, charlie, diana));

        System.out.println("=== Splitwise Demo with Simplified Settlement ===\n");

        // Example 1: Even split
        System.out.println("1. Alice pays $120 for dinner (split evenly among Bob, Charlie, Diana)");
        tripGroup.recordExpense(
                alice,
                Arrays.asList(bob, charlie, diana),
                120.0,
                new EvenSplitStrategy()
        );

        // Example 2: Weighted split
        System.out.println("2. Bob pays $100 for taxi (weighted: Charlie 60%, Diana 40%)");
        tripGroup.recordExpense(
                bob,
                Arrays.asList(charlie, diana),
                100.0,
                new WeighedSplitStrategy(Arrays.asList(
                        new ExpenseWeight(charlie, 60.0),
                        new ExpenseWeight(diana, 40.0)
                ))
        );

        // Example 3: Another even split
        System.out.println("3. Charlie pays $90 for hotel (split evenly between Alice and Bob)");
        tripGroup.recordExpense(
                charlie,
                Arrays.asList(alice, bob),
                90.0,
                new EvenSplitStrategy()
        );

        // Example 4: More transactions
        System.out.println("4. Diana pays $80 for breakfast (split evenly among Alice, Bob, Charlie)");
        tripGroup.recordExpense(
                diana,
                Arrays.asList(alice, bob, charlie),
                80.0,
                new EvenSplitStrategy()
        );


        // NEW: Print simplified settlement
        tripGroup.printSettlementPlan();

        // NEW: Show comparison
        tripGroup.printSettlementComparison();

        // Demonstrate individual checks
        System.out.println("\n=== Checking Bob's balance specifically ===");
        tripGroup.printRequiredSettlement(bob);
    }
};