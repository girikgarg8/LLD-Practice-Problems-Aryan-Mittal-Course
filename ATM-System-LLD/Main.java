import java.util.Scanner;

enum CardType {
    DEBIT, CREDIT
};

class BankAccount {
    private final String number;
    private int balance;

    public BankAccount(String number, int balance) {
        this.number = number;
        this.balance = balance;
    }

    public int getBalance() {
        return balance;
    }

    public boolean debit(int amount) {
        if (amount<=0 || amount>balance) return false;
        balance-=amount;
        return true;
    }
};

abstract class PaymentCard {
    private final String number;
    private final CardType type;
    private String pin;
    private final BankAccount bankAccount;

    public PaymentCard(CardType type, String number, String pin, BankAccount bankAccount) {
        this.type = type;
        this.number = number;
        this.pin = pin;
        this.bankAccount = bankAccount;
    }

    public String getPin() {
        return pin;
    }

    public CardType getType() {
        return type;
    }

    public BankAccount getBankAccount() {
        return bankAccount;
    }

};

class DebitCard extends PaymentCard {
    public DebitCard (String number, String pin, BankAccount bankAccount) {
        super(CardType.DEBIT, number, pin, bankAccount);
    }
};

class CreditCard extends PaymentCard {
    public CreditCard (String number, String pin, BankAccount bankAccount) {
        super(CardType.CREDIT, number, pin, bankAccount);
    }
};

// CHANGE: If we closely observe each state, every state does some business processing followed by transition to next state.
// So, instead of having two methods, let's encapsulate them into one method and return an object of "StateResult" which will have the details of success: true/false, the next state and message (if any)
interface ATMState {
    public StateResult run(ATMController controller);
};

record StateResult (boolean success, ATMState next, String message) {
    // move to next state
    static StateResult next(ATMState state) {
        return new StateResult(true, state, null);
    }

    // stay at current state
    static StateResult stay(String message) {
        return new StateResult(false, null, message);
    }

    // move to idle state (home page)
    static StateResult toIdle(String message) {
        return new StateResult(false, new IdleState(), message);
    }
};

// CHANGE: Instead of creating multiple Scanner objects in different states and duplicating logic of taking user input,
// define a class for it
final class Input {
    private final Scanner scanner = new Scanner(System.in);

    // Print a prompt and take user input, return the user input
    String line(String prompt) {
        System.out.println(prompt);
        return scanner.nextLine();
    }

    int positiveInput(String prompt) {
        while (true) {
            System.out.println(prompt);
            String input = scanner.nextLine();
            try {
                int value = Integer.parseInt(input.trim());
                if (value > 0) return value;
                System.out.println("Amount must be positive");
            }
            catch (NumberFormatException ex) {
                System.out.println("Please enter a valid number");
            }
        }
    }
};

class IdleState implements ATMState {

    // Method to check whether the card has been inserted or not.
    // In real life scenario, this would be determined by a sensor output
    private boolean isCardInserted() {
        return true;
    }

    // dummy method to return the card details. In real world, the sensor would read the data and return
    private PaymentCard getCardDetails() {
        BankAccount bankAccount = new BankAccount("ACCT-001",10000);
        PaymentCard debitCard = new DebitCard("112233", "1234", bankAccount);
        return debitCard;
    }

    @Override
    public StateResult run(ATMController controller) {
        System.out.println("Insert card");
        if (!isCardInserted()) return StateResult.stay("No card detected");
        PaymentCard card = getCardDetails();
        controller.setPaymentCard(card);
        System.out.println("Card read. Proceeding");
        return StateResult.next(new SelectionState());
    }
};


// CHANGED: Selection state loops until valid operation, returns the next chosen state
class SelectionState implements ATMState {

    @Override
    public StateResult run(ATMController controller) {
        while (true) {
            String operation = controller.input().line("Enter operation: (BALANCE_CHECK/WITHDRAWAL/EXIT)");
            switch (operation) {
                case "BALANCE_CHECK" : return StateResult.next(new BalanceCheckState());
                case "WITHDRAWAL": return  StateResult.next(new WithdrawalState());
                case "EXIT" : {
                    controller.ejectCard();
                    return StateResult.next(new IdleState());
                }
                default: System.out.println("Invalid input, please try again");
            }
        }
    }
};

interface AuthenticationService {
    public boolean authenticate(PaymentCard paymentCard, String pin);
};

class AuthenticationServiceImpl implements AuthenticationService {
    @Override
    public boolean authenticate(PaymentCard paymentCard, String pin) {
        return paymentCard.getPin().equals(pin);
    }
};

class BalanceCheckState implements ATMState {
    private final PinVerifier pinVerifier = new PinVerifier();

    @Override
    public StateResult run(ATMController controller) {
        StateResult verifyStatus = pinVerifier.verify(controller, 3);
        if (!verifyStatus.success()) return verifyStatus;
        BankAccount bankAccount = controller.getPaymentCard().getBankAccount();
        System.out.println("Bank account balance: "+ bankAccount.getBalance());
        // after completion, go back to selection
        return StateResult.next(new SelectionState());
    }
};

// CHANGED: Reusable pin verifier with retry/lockout
final class PinVerifier {
    private final AuthenticationService authenticationService = new AuthenticationServiceImpl();

    StateResult verify(ATMController controller, int maxAttempts) {
        for (int i=1; i<=maxAttempts; i++) {
            String pin = controller.input().line("Enter PIN:");
            if (authenticationService.authenticate(controller.getPaymentCard(), pin)) {
                return new StateResult(true, null, "Authentication successful");
            }
            System.out.println("Invalid PIN: Attempt: " + i + " out of " + maxAttempts);
        }
        controller.ejectCard();
        return StateResult.toIdle("Too many invalid attempts. Card locked");
    }

};

class WithdrawalState implements ATMState {
    private final PinVerifier pinVerifier = new PinVerifier();

    @Override
    public StateResult run(ATMController controller) {
        int amount = controller.input().positiveInput("Enter amount to withdraw");
        if (!controller.canDisburse(amount)) {
            return new StateResult(false, new SelectionState(), "ATM cannot disburse that amount");
        }
        StateResult verifyStatus = pinVerifier.verify(controller, 3);
        if (!verifyStatus.success()) return verifyStatus;

        BankAccount bankAccount = controller.getPaymentCard().getBankAccount();
        if (!bankAccount.debit(amount)) return new StateResult(false, new SelectionState(), "Insufficient funds");
        System.out.println("Cash disbursed: "+ amount);
        return StateResult.next(new SelectionState());
    }
};

class ATMController {
    private ATMState currentState = new IdleState();
    private PaymentCard paymentCard;
    private final Input input = new Input();

    public void setCurrentState(ATMState state) {
        this.currentState = state;
    }

    public void setPaymentCard(PaymentCard paymentCard) {
        this.paymentCard = paymentCard;
    }

    public PaymentCard getPaymentCard() {
        return paymentCard;
    }

    public void ejectCard() {
        this.paymentCard = null;
        System.out.println("Card ejected");
    }

    // Dummy method to check if the given amount can be disbursed.
    // In real life implementation, a recursive method can be invoked to check if the given amount can be disbursed from the available set of notes, similar to "Target Sum" problem
    public boolean canDisburse(int amount) {
        return true;
    }

    public Input input() {
        return input;
    }

    // CHANGED: One Step execution of state with error handling
    public void run() {
        try {
            StateResult stateResult = currentState.run(this);
            if (stateResult.message() !=null) System.out.println(stateResult.message());

            if (stateResult.next() !=null) {
                currentState = stateResult.next();
            }
            else {
                // no transition requested → go Idle
                currentState = new IdleState();
            }
        }
        catch (Exception ex) {
            System.out.println("Unexpected error: "+ ex.getMessage());
            currentState = new IdleState();
            ejectCard();
        }
    }
}



public class Main {
    public static void main(String [] args) {
        ATMController atmController = new ATMController();
        while (true) {
            atmController.run();
        }
    }
}
