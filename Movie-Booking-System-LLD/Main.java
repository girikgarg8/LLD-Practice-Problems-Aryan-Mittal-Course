import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.util.concurrent.atomic.*;

enum SeatCategory {
    GOLD, SILVER, BRONZE
};

class Theatre {
    private final String id;
    private final List<Screen> screens;

    public Theatre(String id, List<Screen> screens) {
        if (id == null || screens == null) {
            throw new IllegalArgumentException("Theatre ID and/or screens cannot be null");
        }
        this.id = id;
        // CHANGE: Add Collections.unmodifiableList, to prevent external modifications of internal state by external client
        this.screens = Collections.unmodifiableList(new ArrayList<>(screens));
    }
};

class Screen {
    private final String id;
    private final List <Seat> seats;

    public Screen(String id, List <Seat> seats) {
        if (id == null || seats == null ) throw new IllegalArgumentException("Screen ID and/or seats cannot be null");
        this.id = id;
        this.seats = Collections.unmodifiableList(new ArrayList<>(seats));
    }

    // CHANGED: Added getter, no need to return copy, since during setter, we set it as Collections.unmodifiableList
    public List<Seat> getSeats() {
        return seats;
    }
};

class Seat {
    private final String id;
    private final SeatCategory seatCategory;

    public Seat(String id, SeatCategory seatCategory) {
        if (id == null || seatCategory == null) {
            throw new IllegalArgumentException("Seat id and/or category cannot be null");
        }
        this.id = id;
        this.seatCategory = seatCategory;
    }

    public String getId() {
        return id;
    }
};

class Movie {
    private final String id;
    private final String title;

    public Movie(String id, String title) {
        if (id == null || title == null) {
            throw new IllegalArgumentException("Movie id and title cannot be null");
        }
        this.id = id;
        this.title = title;
    }
};

class Show {
    private final String id;
    private final Movie movie;
    private final Screen screen;

    public Show(String id, Movie movie, Screen screen) {
        if (id == null || movie == null || screen == null) {
            throw new IllegalArgumentException("Show parameters cannot be null");
        }
        this.id = id;
        this.movie = movie;
        this.screen = screen;
    }

    public String getId() {
        return id;
    }
};

class SeatLock {
    private final Instant acquiredAt;
    private final Integer duration;
    private final String userId;

    public SeatLock(Instant acquiredAt, Integer duration, String userId) {
        this.acquiredAt = acquiredAt;
        this.duration = duration;
        this.userId = userId;
    }

    public boolean isValid() {
        Instant expirationTime = acquiredAt.plus(Duration.ofSeconds(duration));
        Instant currentTime = Instant.now();
        return currentTime.isBefore(expirationTime);
    }
};

class ShowSeat {
    private final Show show;
    private final Seat seat;

    public ShowSeat(Show show, Seat seat) {
        this.show = show;
        this.seat = seat;
    }

    @Override
    public int hashCode() {
        // CHANGED: Do not hash over objects show and seat, but rather over id's. Why? Because Java has a contract that If a.equals(b) is true, then a.hashCode() == b.hashCode() MUST be true
        return Objects.hash(show.getId(), seat.getId());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        // CHANGE: Add missing typecast check, if typecast without check, a ClassCastException will be thrown
        if (!(obj instanceof ShowSeat)) return false;
        ShowSeat other = (ShowSeat) obj;
        if (other.show.getId().equals(this.show.getId()) && other.seat.getId().equals(this.seat.getId())) return true;
        return false;
    }

    // CHANGED: Added toString for debugging
    @Override
    public String toString() {
        return "ShowSeat{show=" + show.getId() + ", seat=" + seat.getId() + "}";
    }
};

class Booking {
    private final String id;
    private final ShowSeat showSeat;
    // CHANGED: Add fields for booked at and user ID
    private final Instant bookedAt;
    private final String userId;

    public Booking(String id, ShowSeat showSeat, String userId) {
        this.id = id;
        this.showSeat = showSeat;
        this.userId = userId;
        this.bookedAt = Instant.now();
    }
};

interface PaymentStrategy {
    public CompletableFuture <Boolean> pay();
};

class UPIPaymentStrategy implements PaymentStrategy {
    // CHANGED: Made configurable for testing
    private final long delayMillis;
    private final boolean shouldSucceed;

    public UPIPaymentStrategy(long delayMillis, boolean shouldSucceed) {
        this.delayMillis = delayMillis;
        this.shouldSucceed = shouldSucceed;
    }

    public UPIPaymentStrategy() {
        this(2000, true); // Default: 2 seconds, success
    }

    @Override
    public CompletableFuture<Boolean> pay() {
        // CHANGED: Use CompletableFuture properly with async execution
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                System.out.println("Payment interrupted: " + ex.getMessage());
                return false;
            }
            return shouldSucceed;
        });
    }
}

class BookingManager {
    private final Integer LOCK_TIMEOUT = 300; // 300 seconds
    private final Integer PAYMENT_TIMEOUT = 240; // 240 seconds, deliberately setting the payment timeout lower than lock timeout
    private final Map <ShowSeat, SeatLock> seatLocks = new ConcurrentHashMap<>();
    private final Map <ShowSeat, Booking> bookings = new ConcurrentHashMap<>();

    // CHANGE: We need to ensure that there is no memory leak, and need to periodically clear the expired locks with the help of scheduler thread pool executor
    private final ScheduledExecutorService cleanupScheduler = Executors.newScheduledThreadPool(1);

    // CHANGE: Create mapping of showseat and lock
    private final Map <ShowSeat, Object> lockObjects = new ConcurrentHashMap<>();

    public BookingManager() {
        cleanupScheduler.scheduleWithFixedDelay(this::cleanupExpiredLocks, 60, 60, TimeUnit.SECONDS);
    }

    private void cleanupExpiredLocks() {
        seatLocks.entrySet().removeIf(entry -> !entry.getValue().isValid());
        System.out.println("Cleaned up expired locks, active locks" + seatLocks.size());
    }

    private Object getLockObject(ShowSeat showSeat) {
        return lockObjects.computeIfAbsent(showSeat, k -> new Object());
    }

    // Making check and create as atomic operation to prevent race conditions
    private boolean checkAndCreateSeatLock(ShowSeat showSeat, String userId) {
        // CHANGE: Critical bug earlier! A new showSeat object was getting created earlier, and using synchronized block over it will not ensure mutual exclusion
        Object lock = getLockObject(showSeat);
        synchronized(lock) {
            SeatLock existingLock = seatLocks.get(showSeat);
            if (existingLock == null || !existingLock.isValid()) {
                seatLocks.put(showSeat, new SeatLock(Instant.now(), LOCK_TIMEOUT, userId));
                return true;
            }
            return false;
        }
    }

    // CHANGE: Add lock release mechanism, eg: in case the payment fails or timeouts, the lock can be released
    private void releaseSeatLock(ShowSeat showSeat) {
        seatLocks.remove(showSeat);
        System.out.println("Released seat lock for: "+ showSeat);
    }

    private boolean checkAndCreateBooking(ShowSeat showSeat, PaymentStrategy paymentStrategy, String userId) {
        Object lock = getLockObject(showSeat);
        synchronized(lock) {
            if (bookings.containsKey(showSeat)) {
                System.out.println("Booking already exists for this show and seat combination");
                releaseSeatLock(showSeat);
                return false;
            }
            else {
                try {
                    CompletableFuture <Boolean> paymentFuture = paymentStrategy.pay();
                    Boolean paymentStatus = paymentFuture.get(PAYMENT_TIMEOUT, TimeUnit.SECONDS);
                    if (paymentStatus == null || !paymentStatus) {
                        System.out.println("Payment declined by bank, cannot create booking");
                        releaseSeatLock(showSeat); // CHANGED: Release lock on failure
                        return false;
                    }
                }
                catch (InterruptedException | ExecutionException e) {
                    System.err.println("Error while processing payment : " + e + " cannot create booking");
                    releaseSeatLock(showSeat); // CHANGED: Release lock on failure
                    return false;
                } catch (TimeoutException e) {
                    System.err.println("Payment operation timed out, cannot create booking");
                    releaseSeatLock(showSeat); // CHANGED: Release lock on failure
                    return false;
                }

                Booking booking = new Booking(UUID.randomUUID().toString(), showSeat, userId);
                bookings.put(showSeat, booking);
                releaseSeatLock(showSeat); // CHANGED: Release lock after successful booking
                return true;
            }
        }
    }

    public boolean createBooking(Show show, Seat seat, PaymentStrategy paymentStrategy, String userId) {
        ShowSeat showSeat = new ShowSeat(show, seat);

        if (!checkAndCreateSeatLock(showSeat, userId)) {
            System.out.println("Failed to acquire lock for: " + showSeat);
            return false;
        }
        System.out.println("Lock acquired by " + userId + " for: " + showSeat);

        return checkAndCreateBooking(showSeat, paymentStrategy, userId);
    }

    public int getActiveBookingsCount() {
        return bookings.size();
    }

    public int getActiveLocksCount() {
        return seatLocks.size();
    }

    public void shutdown() {
        cleanupScheduler.shutdown();
    }
};

public class Main {
    public static void main(String [] args) throws InterruptedException {
        System.out.println("=== Movie Booking System Test ===\n");

        // Setup test data
        Seat seat1 = new Seat("A1", SeatCategory.GOLD);
        Seat seat2 = new Seat("A2", SeatCategory.SILVER);
        List<Seat> seats = Arrays.asList(seat1, seat2);

        Screen screen = new Screen("Screen-1", seats);
        Movie movie = new Movie("M1", "Inception");
        Show show = new Show("S1", movie, screen);

        BookingManager bookingManager = new BookingManager();

        // Test 1: Successful booking
        System.out.println("Test 1: Single successful booking");
        boolean result1 = bookingManager.createBooking(
                show, seat1,
                new UPIPaymentStrategy(1000, true),
                "User-1"
        );
        System.out.println("Booking result: " + result1);
        System.out.println("Active bookings: " + bookingManager.getActiveBookingsCount() + "\n");

        // Test 2: Double booking attempt (should fail)
        System.out.println("Test 2: Attempt double booking on same seat");
        boolean result2 = bookingManager.createBooking(
                show, seat1,
                new UPIPaymentStrategy(1000, true),
                "User-2"
        );
        System.out.println("Booking result: " + result2 + " (should be false)");
        System.out.println("Active bookings: " + bookingManager.getActiveBookingsCount() + "\n");

        // Test 3: Concurrent booking attempts on same seat
        System.out.println("Test 3: 5 concurrent threads trying to book same seat");
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < 5; i++) {
            final int userId = i;
            executor.submit(() -> {
                try {
                    boolean success = bookingManager.createBooking(
                            show, seat2,
                            new UPIPaymentStrategy(500, true),
                            "User-" + userId
                    );
                    if (success) {
                        successCount.incrementAndGet();
                        System.out.println("✓ User-" + userId + " succeeded!");
                    } else {
                        System.out.println("✗ User-" + userId + " failed (expected)");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        System.out.println("\nSuccessful bookings: " + successCount.get() + " (should be 1)");
        System.out.println("Total bookings: " + bookingManager.getActiveBookingsCount() + "\n");

        // Test 4: Payment failure scenario
        System.out.println("Test 4: Payment failure - lock should be released");
        Seat seat3 = new Seat("B1", SeatCategory.BRONZE);
        boolean result4 = bookingManager.createBooking(
                show, seat3,
                new UPIPaymentStrategy(1000, false), // Payment fails
                "User-10"
        );
        System.out.println("Booking result: " + result4 + " (should be false)");

        // Try again after payment failure
        boolean result5 = bookingManager.createBooking(
                show, seat3,
                new UPIPaymentStrategy(1000, true), // Payment succeeds
                "User-11"
        );
        System.out.println("Retry booking result: " + result5 + " (should be true)\n");

        System.out.println("=== Final Statistics ===");
        System.out.println("Total successful bookings: " + bookingManager.getActiveBookingsCount());
        System.out.println("Active locks: " + bookingManager.getActiveLocksCount());

        bookingManager.shutdown();
    }
};