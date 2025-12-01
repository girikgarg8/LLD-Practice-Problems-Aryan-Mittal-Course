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

    public long getBalance() {
        return balance;
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
    private String cvv;

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

    public String getCvv() {
        return cvv;
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
            case WITHDRAWAL: return new StateResult(new WithdrawalState(new CVVBasedAuthorizationService()), true, "Transitioning to withdrawal operation");
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
        // TODO: Can use String.format here
        return new StateResult(new HomeState(), true, "Your balance is: " + balance + ", transitioning back to home state");
    }
}

class WithdrawalState implements ATMState {
    private final AuthorizationService authorizationService;

    public WithdrawalState(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public StateResult execute(ATMController controller) {
        PaymentCard paymentCard = controller.getPaymentCard();
        if (paymentCard == null) return new StateResult(new HomeState(), false, "Please insert your card before proceeding");
        TransactionContext transactionContext = controller.getTransactionContext();
        if (transactionContext == null) return new StateResult(this, false, "Please make sure the transaction context is set to proceed for withdrawal");
        // For now, not having the check of checking the ATM's remaining balance. Assuming infinite supply of money with the ATM
        if (transactionContext.getRequestedAmount() > paymentCard.getBankAccount().getBalance())
            return new StateResult(this, false, "Requested amount greater than available balance. Please retry with valid amount");

        boolean authorizationResult = authorizationService.authorize(transactionContext, paymentCard);
        if (!authorizationResult) return new StateResult(this, false, "Authorization failed. Please check your input(s) and retry");
        return new StateResult(new DispensingState(), true, "Transitioning to dispensing state");
    }
};

class DispensingState implements ATMState {
    @Override
    public StateResult execute(ATMController controller) {
        TransactionContext transactionContext = controller.getTransactionContext();
        if (transactionContext == null) return new StateResult(this, false, "Please make sure the transaction context is set to proceed for dispension");
        controller.resetContext();
        controller.ejectCard();
        return new StateResult(this, true, "Successfully dispensed amount, going back to home state");
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
        return transactionContext.getCvvInput().equals(paymentCard.getCvv());
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

    public void insertCard(PaymentCard paymentCard) {
        if (paymentCard == null) throw new IllegalArgumentException("Payment card cannot be null");
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
        return stateResult;
        // This ATM Controller class is like an computation engine which is not tightly coupled to any specific mode of input (CLI/GUI/REST) etc.
        // It simply executes the currentState and returns the state result. Printing the message, error etc is responsibility of the Integration layer like CLI, GUI etc
    }

    public TransactionContext getTransactionContext() {
        return transactionContext;
    }
}

public class Main {
    public static void main(String [] args) {



    }
}