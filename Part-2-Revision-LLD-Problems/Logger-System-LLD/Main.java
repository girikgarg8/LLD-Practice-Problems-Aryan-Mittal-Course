import java.util.*;
import java.time.*;
import java.util.stream.*;
import java.util.concurrent.*;

enum LogLevel {
    FATAL(5), ERROR(4), WARN(3), INFO(2), DEBUG(1);

    private final int severity;

    LogLevel(int severity) {
        this.severity = severity;
    }

    public boolean hasMinimumSeverity(LogLevel level) {
        return this.severity >= level.severity;
    }
}

class LogMessage {
    private final Instant timestamp;
    private final LogLevel level;
    private final String data;

    public LogMessage(Instant timestamp, LogLevel level, String data) {
        if (timestamp == null || level == null || data == null || data.trim().isEmpty()) throw new IllegalArgumentException("Invalid argument(s) passed for log message");
        this.timestamp = timestamp;
        this.level = level;
        this.data = data;
    }

    public LogLevel getLevel() {
        return level;
    }

    @Override
    public String toString() {
        return "Log {level= " + level + ", timestamp= " + timestamp + ", message= " + data + " }";
    }
}

interface LogWriter {
    public void write(List <LogMessage> logs);
}

// Don’t double-parallelize inside writers: Writers should be simple and fast; parallelism is already controlled by the manager’s executor.
class SplunkLogWriter implements LogWriter {
    @Override
    public void write(List <LogMessage> logs) {
        logs.stream().forEach(log -> System.out.println("Exporting log: " + log + " to Splunk"));
    }
}

class CloudWatchLogWriter implements LogWriter {
    @Override
    public void write(List <LogMessage> logs) {
        logs.stream().forEach(log -> System.out.println("Exporting log: " + log + " to Cloudwatch"));
    }
}

interface LogFilter {
    public List <LogMessage> filter(List <LogMessage> logs);
}

class SeverityLogFilter implements LogFilter {
    private LogLevel minimumLevel;

    public SeverityLogFilter(LogLevel minimumLevel) {
        setMinimumLevel(minimumLevel);
    }

    public void setMinimumLevel(LogLevel minimumLevel) {
        if (minimumLevel == null) throw new IllegalArgumentException("Minimum log level cannot be null");
        this.minimumLevel = minimumLevel;
    }

    @Override
    public List <LogMessage> filter(List <LogMessage> logs) {
        return logs.stream().filter(log -> log.getLevel().hasMinimumSeverity(minimumLevel)).collect(Collectors.toList());
    }

}

interface LoggingManager {
    // Add add other methods like addLogFilter, addLogWriter etc in the interface definition, since they would be common to all
    public void addLogWriter(LogWriter writer);
    public void addLogFilter(LogFilter filter);
    public CompletableFuture <Void> log(List <LogMessage> logs);
}

class LoggingManagerImpl implements LoggingManager {
    private List <LogFilter> logFilters = new ArrayList<>();
    private List <LogWriter> logWriters = new ArrayList<>();

    @Override
    public void addLogFilter(LogFilter filter) {
        if (filter == null) throw new IllegalArgumentException("Log filter cannot be null");
        logFilters.add(filter);
    }

    @Override
    public void addLogWriter(LogWriter writer) {
        if (writer == null) throw new IllegalArgumentException("Log writer cannot be null");
        logWriters.add(writer);
    }

    @Override
    public CompletableFuture <Void> log(List <LogMessage> logs) {
        List <LogMessage> filteredLogs = new ArrayList<>(logs);

        for (LogFilter logFilter: logFilters) {
            filteredLogs = logFilter.filter(Collections.unmodifiableList(filteredLogs));
        }

        List <CompletableFuture> writerFutures = new ArrayList<>();

        for (LogWriter writer: logWriters) {
            final List <LogMessage> exportableLogs = filteredLogs;
            writerFutures.add(CompletableFuture.runAsync(() -> {
                writer.write(exportableLogs);
            }));
        }

        return CompletableFuture.allOf(writerFutures.toArray(new CompletableFuture[0]));
    }
}

public class Main {
    public static void main(String [] args) throws InterruptedException {
        LoggingManager manager = new LoggingManagerImpl();

        // Configure: filter out DEBUG, add writers
        manager.addLogFilter(new SeverityLogFilter(LogLevel.INFO));
        manager.addLogWriter(new SplunkLogWriter());
        manager.addLogWriter(new CloudWatchLogWriter());

        // Single batch (DEBUG should be filtered out)
        List<LogMessage> batch = Arrays.asList(
                new LogMessage(Instant.now(), LogLevel.DEBUG, "debug - should be filtered"),
                new LogMessage(Instant.now(), LogLevel.INFO, "info - visible"),
                new LogMessage(Instant.now(), LogLevel.ERROR, "error - visible")
        );
        manager.log(batch).join();
        System.out.println("Single batch complete.\n");

        // Concurrent batches (just read-only logging; no concurrent reconfiguration)
        ExecutorService es = Executors.newFixedThreadPool(4);
        List<Callable<Void>> tasks = IntStream.range(0, 5)
                .mapToObj(i -> (Callable<Void>) () -> {
                    List<LogMessage> logs = Arrays.asList(
                            new LogMessage(Instant.now(), LogLevel.INFO, "info " + i),
                            new LogMessage(Instant.now(), LogLevel.WARN, "warn " + i)
                    );
                    manager.log(logs).join();
                    return null;
                })
                .collect(Collectors.toList());

        es.invokeAll(tasks);
        es.shutdown();
        es.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("Concurrent batches complete.");
    }
}