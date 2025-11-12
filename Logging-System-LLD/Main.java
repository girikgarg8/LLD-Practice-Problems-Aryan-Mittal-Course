import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.*;
import java.util.concurrent.*;

enum LogLevel {
    DEBUG(0), INFO(1), WARN(2), ERROR(3);

    private int severity;

    LogLevel(int severity) {
        this.severity = severity;
    }

    public int getSeverity() {
        return severity;
    }

    // CHANGE: Centralize the comparison logic instead of every caller doing the comparison
    public boolean atLeast (LogLevel other) {
        return this.severity >= other.severity;
    }
};

enum LogSource {
    ACCOUNT_SERVICE, BILLING_SERVICE, ORDER_SERVICE
};

class LogMessage {
    private final String content;
    private final LogLevel level;
    private final long timestamp;
    private final LogSource source;

    public LogMessage(String content, LogLevel level, long timestamp, LogSource source) {
        this.content = content;
        this.level = level;
        this.timestamp = timestamp;
        this.source = source;
    }

    public String getContent() {
        return content;
    }

    public LogLevel getLevel() {
        return level;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public LogSource getSource() {
        return source;
    }

    @Override
    public String toString() {
        return "Level: " + level + " TimeStamp: " + timestamp + " Source: " + source + " Content: " + content;
    }
};

interface LogHandler {
    public List <LogMessage> apply(List <LogMessage> logMessages);
};

class SeverityLogHandler implements LogHandler {
    private final LogLevel minLevel;

    public SeverityLogHandler(LogLevel minLevel) {
        this.minLevel = minLevel;
    }

    @Override
    public List <LogMessage> apply(List <LogMessage> logMessages) {
        return logMessages.stream()
                .filter(logMessage -> logMessage.getLevel().atLeast(minLevel))
                .collect(Collectors.toList());
    }
};

// CHANGE: Handler name suggests to "mask" PII, so just redact the PII part instead of filtering out the entire log message
class PiiMaskingHandler implements LogHandler {
    private final static Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final String REDACTED = "[REDACTED]";

    @Override
    public List <LogMessage> apply(List <LogMessage> logMessages) {
        return logMessages.stream()
                .map(message -> {
                    String masked = EMAIL_PATTERN.matcher(message.getContent()).replaceAll(REDACTED);
                    // If nothing changed, reuse the original instance
                    if (masked.equals(message.getContent())) return message;
                    // Create a new log message object with only the content changed to the masked content. Rest of the properties stay same
                    return new LogMessage(masked, message.getLevel(), message.getTimestamp(), message.getSource());
                })
                .collect(Collectors.toList());
    }
};

interface LogWriter {
    public Boolean writeBatch(List <LogMessage> logMessages);
};

class FileLogWriter implements LogWriter {
    @Override
    public Boolean writeBatch(List <LogMessage> logMessages ) {
        logMessages.parallelStream().forEach(logMessage -> System.out.println("Printing log to file: " + logMessage.toString()));
        return true;
    }
};

class CloudLogWriter implements LogWriter {

    @Override
    public Boolean writeBatch(List <LogMessage> logMessages ) {
        logMessages.parallelStream().forEach(logMessage -> System.out.println("Printing log to cloudwatch: " + logMessage.toString()));
        return true;
    }
};

interface LoggingSystem {
    public void addLogHandler(LogHandler logHandler);
    public void addLogWriter(LogWriter logWriter);
    public void removeLogHandler(LogHandler logHandler);
    public void removeLogWriter(LogWriter logWriter);
    public CompletableFuture <Void> log(List <LogMessage> logMessages);
};


class LoggingSystemImpl implements LoggingSystem {
    // keeping as set instead of list to maintain uniqueness, non-idempotent operations can have side effects if there are duplicate elements
    // CHANGE: Instead of storing the log handlers in a hashset(which can keep the entries in any order), it is recommended to store them inside a LinkedHashSet.
    // Reason being that LinkedHashSet stores the elements in the insertion order, and helps prevent side effects due to different execution order of handlers. A,B might not produce the same output as B,A
    private final Set <LogHandler> handlers = new LinkedHashSet<>();
    private final Set <LogWriter> writers = new HashSet<>();
    private final int MAX_ATTEMPTS_COUNT = 3;

    @Override
    public void addLogHandler(LogHandler logHandler) {
        handlers.add(logHandler);
    }

    @Override
    public void addLogWriter(LogWriter logWriter) {
        writers.add(logWriter);
    }

    @Override
    public void removeLogHandler(LogHandler logHandler) {
        handlers.remove(logHandler);
    }

    @Override
    public void removeLogWriter(LogWriter logWriter) {
        writers.remove(logWriter);
    }

    private List <LogMessage> preHandle(List <LogMessage> logMessages) {
        List <LogMessage> output = logMessages;

        for (LogHandler logHandler: handlers) {
            output = logHandler.apply(output);
        }

        return output;
    }

    // CHANGE: Make the retry logic iterative instead of recursive for better understandability
    private void retryWrite(LogWriter logWriter, List <LogMessage> logMessages, int maxAttempts) {
        int attempts = 0;
        // First iteration is the pre-call, rest are retries
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (logWriter.writeBatch(logMessages)) return;
        }
        System.out.println("Max attempts reached, aborting");
    }

//    private void exportLogs(List <LogMessage> logMessages) {
//        for (LogWriter logWriter: logWriters) {
//
//            CompletableFuture.runAsync(() -> {
//                retryWrite(logWriter, logMessages, MAX_ATTEMPTS_COUNT);
//            });
//        }
//    }

    @Override
    // CHANGE: Return CompletableFuture so that client has visibility into export status. Once the logs have been exported to all the writers, clients havew a way to know
    public CompletableFuture <Void> log(List <LogMessage> logMessages) {
        List <LogMessage> processed = preHandle(logMessages);
        // CHANGE: Don't make the mistake of exporting original logs instead of the processed ones :)
        List <CompletableFuture<Void>> futures = writers.stream()
                                                            .map(w -> CompletableFuture.runAsync(() -> retryWrite(w, processed, MAX_ATTEMPTS_COUNT)))
                                                            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }
};

public class Main {
    public static void main(String [] args) {
        // Build a few sample logs
        List<LogMessage> logs = Arrays.asList(
                new LogMessage("Startup complete", LogLevel.INFO, System.currentTimeMillis(), LogSource.ACCOUNT_SERVICE),
                new LogMessage("Payment failed for user alice@example.com", LogLevel.WARN, System.currentTimeMillis(), LogSource.BILLING_SERVICE),
                new LogMessage("Debug trace A", LogLevel.DEBUG, System.currentTimeMillis(), LogSource.ORDER_SERVICE),
                new LogMessage("Contact bob.smith@company.co.uk for support", LogLevel.ERROR, System.currentTimeMillis(), LogSource.ACCOUNT_SERVICE),
                new LogMessage("alice@example.com", LogLevel.INFO, System.currentTimeMillis(), LogSource.BILLING_SERVICE) // entire content is an email
        );

        // Configure logging system
        LoggingSystem logging = new LoggingSystemImpl();

        // Handlers:
        // 1) Keep INFO and above (filters out DEBUG)
        logging.addLogHandler(new SeverityLogHandler(LogLevel.INFO));
        // 2) Mask PII (email masking)
        logging.addLogHandler(new PiiMaskingHandler());

        // Writers (file + cloud)
        logging.addLogWriter(new FileLogWriter());
        logging.addLogWriter(new CloudLogWriter());

        // Log the batch
        CompletableFuture <Void> loggingFuture = logging.log(logs);
        loggingFuture.join();

    }
}
