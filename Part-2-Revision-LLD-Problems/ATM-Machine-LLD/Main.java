import java.util.*;

class BankAccount {
    private final String id;
    private long balance;

    public BankAccount(String id, long balance) {
        if (id == null || id.trim().isEmpty() || balance < 0) throw new IllegalArgumentException("Invalid argument(s) passed for bank account");
        this.id = id;
        this.balance = balance;
    }

    public String getId() {
        return id;
    }

    public synchronized long getBalance() {
        return balance;
    }

    // UPDATE: Add atomic withdraw to actually deduct balance during dispense. It is atomic in the sense that it checks and deducts as a single atomic operation
    // Using locking for thread safety
    public synchronized boolean withdraw(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Withdraw amount must be positive");
        if (amount > balance) return false;
        balance -= amount;
        return true;
    }

    // UPDATE: Add a method to deposit amount to bank account. To prevent race condition with multiple deposit threads or a deposit and withdraw thread concurrently
    // Use synchronized to synchronize over same monitor lock
    public synchronized void deposit(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Deposit amount must be positive");
        balance += amount;
    }
}

enum CardType {
    DEBIT, CREDIT
}

enum CardOperation {
    BALANCE_CHECK, WITHDRAWAL
}

abstract class PaymentCard {
    private final String id;
    private final CardType type;
    private final BankAccount bankAccount;
    // UPDATE: Make immutable, cvv is the number behind the card which cannot be changed
    private final String cvv;

    public PaymentCard(String id, CardType type, BankAccount bankAccount, String cvv) {
        if (id == null || id.trim().isEmpty() || bankAccount == null || cvv == null || cvv.trim().isEmpty() || type == null)
            throw new IllegalArgumentException("Invalid argument(s) passed for payment card");

        this.id = id;
        this.type = type;
        this.bankAccount = bankAccount;
        this.cvv = cvv;
    }

    public String getId() {
        return id;
    }

    public CardType getType() {
        return type;
    }

    public BankAccount getBankAccount() {
        return bankAccount;
    }

    // UPDATE: Don't expose CVV directly since it's sensitive information. Create method validateCVV which will internally check the cvv and return true/false
    public boolean validateCvv(String input) {
        return input != null && input.equals(cvv);
    }
}

class DebitCard extends PaymentCard {
    public DebitCard(String id, BankAccount bankAccount, String cvv) {
        super(id, CardType.DEBIT, bankAccount, cvv);
    }
}

class CreditCard extends PaymentCard {
    public CreditCard(String id, BankAccount bankAccount, String cvv) {
        super(id, CardType.CREDIT, bankAccount, cvv);
    }
}

class StateResult {
    private final ATMState next;
    private final boolean success;
    private final String message;

    public StateResult(ATMState next, boolean success, String message) {
        if (next == null || message == null || message.trim().isEmpty()) throw new IllegalArgumentException("Invalid argument(s) passed for state result");
        this.next = next;
        this.success = success;
        this.message = message;
    }

    public ATMState getNext() {
        return next;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}

interface ATMState {
    public StateResult execute (ATMController controller);
    default boolean isOperational() {
        return true;
    }
}

class HomeState implements ATMState {
    @Override
    public StateResult execute (ATMController controller) {
        if (!controller.isCardInserted()) return new StateResult(this, false, "Please insert your card to continue");
        return new StateResult(new SelectionState(), true, "Transitioning to selection state");
    }
}

class SelectionState implements ATMState {
    @Override
    public StateResult execute(ATMController controller) {
        CardOperation cardOperation = controller.getCardOperation();
        if (cardOperation == null) return new StateResult(this, false, "Please make your card operation selection");

        switch (cardOperation) {
            case BALANCE_CHECK: return new StateResult(new BalanceCheckState(), true, "Transitioning to balance check operation");
            // UPDATE: Instead of hardwiring the CVV Based Authorization Service, inject the Authorization Service from Controller (Dependency injection)
            case WITHDRAWAL: return new StateResult(new WithdrawalState(controller.getAuthorizationService()), true, "Transitioning to withdrawal operation");
            default: throw new IllegalArgumentException("Card operation not supported");
        }
    }
}

class BalanceCheckState implements ATMState {
    @Override
    public StateResult execute(ATMController controller) {
        PaymentCard paymentCard = controller.getPaymentCard();
        if (paymentCard == null) return new StateResult(new HomeState(), false, "Please insert your card before proceeding");
        long balance = paymentCard.getBankAccount().getBalance();
        return new StateResult(new HomeState(), true, String.format("Your balance is: %d, transitioning back to home state", balance));
    }
}

class WithdrawalState implements ATMState {
    private final AuthorizationService authorizationService;

    public WithdrawalState(AuthorizationService authorizationService) {
        // UPDATE: Add check for null safety
        if (authorizationService == null) throw new IllegalArgumentException("Authorization service cannot be null");
        this.authorizationService = authorizationService;
    }

    @Override
    public StateResult execute(ATMController controller) {
        PaymentCard paymentCard = controller.getPaymentCard();
        if (paymentCard == null) return new StateResult(new HomeState(), false, "Please insert your card before proceeding");
        TransactionContext transactionContext = controller.getTransactionContext();
        if (transactionContext == null) return new StateResult(this, false, "Please make sure the transaction context is set to proceed for withdrawal");
        long requested = transactionContext.getRequestedAmount();

        // UPDATE: First check whether the ATM has cash availability, if available, then only check the user account's balance
        if (!controller.hasSufficientCash(requested)) return new StateResult(this, false, "ATM does not have sufficient cash. Please try a smaller amount or visit another ATM");

        if (requested > paymentCard.getBankAccount().getBalance())
            return new StateResult(this, false, "Requested amount greater than available balance. Please retry with valid amount");

        boolean authorizationResult = authorizationService.authorize(transactionContext, paymentCard);
        if (!authorizationResult) return new StateResult(this, false, "Authorization failed. Please check your input(s) and retry");

        // UPDATE: DO NOT Deduct funds here, transition to dispensing which will atomically deduct
        return new StateResult(new DispensingState(), true, "Transitioning to dispensing state");
    }
};

class DispensingState implements ATMState {
    @Override
    public StateResult execute(ATMController controller) {
        TransactionContext transactionContext = controller.getTransactionContext();
        if (transactionContext == null) return new StateResult(this, false, "Please make sure the transaction context is set to proceed for dispension");

        PaymentCard paymentCard = controller.getPaymentCard();
        if (paymentCard == null) return new StateResult(new HomeState(), false, "Please insert your card before proceeding");

        long requested = transactionContext.getRequestedAmount();

        // UPDATE: Atomically deduct from account first, then from ATM cash
        boolean debited = paymentCard.getBankAccount().withdraw(requested);
        if (!debited) return new StateResult(new WithdrawalState(controller.getAuthorizationService()), false, "Insufficient funds during dispensing. Returning to withdrawal");

        boolean atmDeducted = controller.deductCash(requested);
        if (!atmDeducted) {
            // UPDATE: Rollback account in rare race(for demo purpose, real systems need proper transactions)
            paymentCard.getBankAccount().deposit(requested);
            return new StateResult(new WithdrawalState(controller.getAuthorizationService()), false, "ATM Cash became insufficient. Returning to withdrawal");
        }

        controller.resetContext();
        controller.ejectCard();

        return new StateResult(new HomeState(), true, "Successfully dispensed amount, going back to home state");
    }
}

class MaintenanceState implements ATMState {
    @Override
    public boolean isOperational() {
        return false;
    }

    @Override
    public StateResult execute(ATMController controller) {
        return new StateResult(this, true, "Currently in non-operational maintenance mode");
    }
}

interface AuthorizationService {
    public boolean authorize(TransactionContext transactionContext, PaymentCard paymentCard);
}

class CVVBasedAuthorizationService implements AuthorizationService {
    @Override
    public boolean authorize(TransactionContext transactionContext, PaymentCard paymentCard) {
        // UPDATE: Use validateCVV instead of exposing raw CVV
        return paymentCard.validateCvv(transactionContext.getCvvInput());
    }
}

class TransactionContext {
    private final String id;
    private final String cvvInput;
    private final String ip;
    private final long requestedAmount;

    public TransactionContext(String id, String cvvInput, String ip, long requestedAmount) {
        if (id == null || id.trim().isEmpty() || cvvInput == null || cvvInput.trim().isEmpty() || ip == null || ip.trim().isEmpty() || requestedAmount < 0)
            throw new IllegalArgumentException("Invalid argument(s) passed for transaction context");

        this.id = id;
        this.cvvInput = cvvInput;
        this.ip = ip;
        this.requestedAmount = requestedAmount;
    }

    public String getId() {
        return id;
    }

    public String getCvvInput() {
        return cvvInput;
    }

    public String getIp() {
        return ip;
    }

    public long getRequestedAmount() {
        return requestedAmount;
    }
}

class ATMController {
    private ATMState currentState;
    private PaymentCard paymentCard;
    private CardOperation cardOperation;
    private TransactionContext transactionContext;
    private final AuthorizationService authorizationService = new CVVBasedAuthorizationService();

    public AuthorizationService getAuthorizationService() {
        return authorizationService;
    }

    // UPDATE: Track ATM Cash on hand
    private long cashOnHand = 100_000L;

    public ATMController() {
        currentState = new HomeState();
    }

    public void setCardOperation(CardOperation cardOperation) {
        if (cardOperation == null) throw new IllegalArgumentException("Card operation cannot be null");
        this.cardOperation = cardOperation;
    }

    public void setTransactionContext(TransactionContext transactionContext) {
        if (transactionContext == null) throw new IllegalArgumentException("Transaction context cannot be null");
        this.transactionContext = transactionContext;
    }

    public CardOperation getCardOperation() {
        return cardOperation;
    }

    public void setCurrentState(ATMState state) {
        if (state == null) throw new IllegalArgumentException("ATM State cannot be null");
        this.currentState = state;
    }

    // UPDATE: Prevent inserting a second card while one is already present
    public void insertCard(PaymentCard paymentCard) {
        if (paymentCard == null) throw new IllegalArgumentException("Payment card cannot be null");
        if (this.paymentCard != null) throw new IllegalArgumentException("A card is already inserted");
        this.paymentCard = paymentCard;
    }

    public void ejectCard() {
        if (this.paymentCard == null) throw new IllegalArgumentException("Cannot eject card when no existing card");
        this.paymentCard = null;
    }

    public boolean isCardInserted() {
        return paymentCard != null;
    }

    public PaymentCard getPaymentCard() {
        return paymentCard;
    }

    public void resetContext() {
        this.cardOperation = null;
        this.transactionContext = null;
    }

    public StateResult execute() {
        StateResult stateResult = currentState.execute(this);
        // UPDATE: Advance the machine to next state
        this.currentState = stateResult.getNext();
        return stateResult;
        // This ATM Controller class is like an computation engine which is not tightly coupled to any specific mode of input (CLI/GUI/REST) etc.
        // It simply executes the currentState and returns the state result. Printing the message, error etc is responsibility of the Integration layer like CLI, GUI etc
    }

    public TransactionContext getTransactionContext() {
        return transactionContext;
    }

    public boolean hasSufficientCash(long amount) {
        return amount > 0 && amount <= cashOnHand;
    }

    public void refillCash(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Refill amount must be positive");
        cashOnHand += amount;
    }

    public long getCashOnHand() {
        return cashOnHand;
    }

    // UPDATED: Maintenance mode toggles
    public void enterMaintenance() {
        this.currentState = new MaintenanceState();
    }

    public void exitMaintenance() {
        this.currentState = new HomeState();
    }

    public boolean deductCash(long amount) {
        if (!hasSufficientCash(amount)) return false;
        cashOnHand -= amount;
        return true;
    }
}

public class Main {
    public static void main(String [] args) {
        // UPDATED: Client-side test harness with safe insert helpers to avoid "card already inserted"
        BankAccount account = new BankAccount("A1", 5_000);
        PaymentCard card = new DebitCard("CARD-1", account, "123");
        ATMController atm = new ATMController();

        System.out.println("=== Balance Check ===");
        ensureFreshInsert(atm, card); // UPDATED
        atm.setCardOperation(CardOperation.BALANCE_CHECK);
        runSteps(atm, 3); // Home -> Selection -> BalanceCheck -> Home

        System.out.println("\n=== Successful Withdrawal (1,200) ===");
        ensureFreshInsert(atm, card); // UPDATED
        atm.setCardOperation(CardOperation.WITHDRAWAL);
        atm.setTransactionContext(new TransactionContext(
                UUID.randomUUID().toString(), "123", "127.0.0.1", 1_200
        ));
        runSteps(atm, 4); // Home -> Selection -> Withdrawal -> Dispensing -> Home
        System.out.println("Post-withdrawal balance: " + account.getBalance());

        System.out.println("\n=== Insufficient Funds (10,000) ===");
        ensureFreshInsert(atm, card); // UPDATED
        atm.setCardOperation(CardOperation.WITHDRAWAL);
        atm.setTransactionContext(new TransactionContext(
                UUID.randomUUID().toString(), "123", "127.0.0.1", 10_000
        ));
        runSteps(atm, 3); // Home -> Selection -> Withdrawal (fails, stays)

        System.out.println("\n=== ATM Insufficient Cash (150,000) ===");
        ensureFreshInsert(atm, card); // UPDATED
        atm.setCardOperation(CardOperation.WITHDRAWAL);
        atm.setTransactionContext(new TransactionContext(
                UUID.randomUUID().toString(), "123", "127.0.0.1", 150_000
        ));
        runSteps(atm, 3);
    }

    // UPDATED: Eject current card if present, then insert the provided card.
    // Also handles the "already inserted" race by catching and retrying after ejecting.
    private static void ensureFreshInsert(ATMController atm, PaymentCard card) {
        if (atm.isCardInserted()) {
            // Best-effort cleanup; eject will succeed when a card is present
            atm.ejectCard();
        }
        try {
            atm.insertCard(card);
        } catch (IllegalStateException alreadyInserted) { // "A card is already inserted"
            // UPDATED: Recover by ejecting then retrying insert
            if (atm.isCardInserted()) atm.ejectCard();
            atm.insertCard(card);
        }
    }

    private static void runSteps(ATMController controller, int steps) {
        for (int i = 0; i < steps; i++) {
            StateResult res = controller.execute();
            System.out.println(res.getMessage());
        }
    }
}

