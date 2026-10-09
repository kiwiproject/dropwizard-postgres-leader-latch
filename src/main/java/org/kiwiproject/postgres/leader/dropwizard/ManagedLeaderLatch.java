package org.kiwiproject.postgres.leader.dropwizard;

import static com.google.common.base.Preconditions.checkArgument;
import static org.kiwiproject.base.KiwiPreconditions.checkArgumentNotBlank;
import static org.kiwiproject.base.KiwiPreconditions.checkArgumentNotNull;
import static org.kiwiproject.base.KiwiPreconditions.requireNotNull;

import com.google.common.base.MoreObjects;
import io.dropwizard.lifecycle.Managed;
import lombok.extern.slf4j.Slf4j;
import org.kiwiproject.postgres.leader.PostgresLeaderLatch;
import org.kiwiproject.postgres.leader.LeaderInfo;
import org.kiwiproject.postgres.leader.LeaderLatch;
import org.kiwiproject.postgres.leader.LeaderLatchConfiguration;
import org.kiwiproject.postgres.leader.LeaderLatchListener;
import org.kiwiproject.postgres.leader.LeadershipStatus;
import org.kiwiproject.postgres.leader.StartResult;
import org.kiwiproject.postgres.leader.WhenLeaderResult;
import org.kiwiproject.postgres.leader.dropwizard.exception.ManagedLeaderLatchException;

import java.sql.Connection;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Wraps a {@link LeaderLatch} so that Dropwizard manages its lifecycle: it is started when the Dropwizard
 * application starts and closed when the application stops.
 * <p>
 * Starting the latch never waits to become the leader. If the latch cannot be started at all, {@link #start()}
 * throws a {@link ManagedLeaderLatchException}, which stops the application from starting. After that, errors such
 * as Postgres being unreachable are not thrown; they are reported through {@link #checkLeadershipStatus()}
 * and {@link #getLeader()}.
 */
@Slf4j
public class ManagedLeaderLatch implements Managed {

    private final LeaderLatch latch;

    /**
     * Wrap an existing latch.
     *
     * @param latch the latch to manage
     * @throws IllegalArgumentException if the latch is null
     */
    public ManagedLeaderLatch(LeaderLatch latch) {
        this.latch = requireNotNull(latch, "latch must not be null");
    }

    /**
     * Construct a latch with a standard ID and leadership key.
     *
     * @param connectionSupplier creates the latch's dedicated connection to the primary database; the latch
     *                          closes every connection it obtains
     * @param configuration     the latch configuration
     * @param serviceDescriptor service metadata; the name is the leadership key, and the name, version, hostname
     *                          and port together make up the participant ID, so none may be blank and the port
     *                          must be positive
     * @param listeners         zero or more listeners to add to the latch before it starts
     * @throws IllegalArgumentException if any argument is null or blank, or the port is not positive
     */
    public ManagedLeaderLatch(Supplier<Connection> connectionSupplier,
                              LeaderLatchConfiguration configuration,
                              ServiceDescriptor serviceDescriptor,
                              LeaderLatchListener... listeners) {
        this(newLatch(connectionSupplier, configuration, serviceDescriptor, listeners));
    }

    /**
     * Construct a latch with a specific ID and the given leadership key.
     * <p>
     * The {@code serviceName} should be the generic name of a service, e.g. "payment-service", instead of a
     * unique identifier, so that all instances of the service contend for the same leadership.
     *
     * @param connectionSupplier creates the latch's dedicated connection to the primary database; the latch
     *                           closes every connection it obtains
     * @param configuration  the latch configuration
     * @param id             the unique ID for this latch participant
     * @param serviceName    the generic name of the service, used as the leadership key
     * @param listeners      zero or more listeners to add to the latch before it starts
     * @throws IllegalArgumentException if any argument is null or blank
     */
    public ManagedLeaderLatch(Supplier<Connection> connectionSupplier,
                              LeaderLatchConfiguration configuration,
                              String id,
                              String serviceName,
                              LeaderLatchListener... listeners) {
        this(newLatch(connectionSupplier, configuration, serviceName, id, listeners));
    }

    private static LeaderLatch newLatch(Supplier<Connection> connectionSupplier,
                                        LeaderLatchConfiguration configuration,
                                        ServiceDescriptor serviceDescriptor,
                                        LeaderLatchListener... listeners) {
        checkArgumentNotNull(serviceDescriptor, "serviceDescriptor must not be null");
        checkArgumentNotBlank(serviceDescriptor.name(), "serviceDescriptor name must not be blank");
        checkArgumentNotBlank(serviceDescriptor.version(), "serviceDescriptor version must not be blank");
        checkArgumentNotBlank(serviceDescriptor.hostname(), "serviceDescriptor hostname must not be blank");
        checkArgument(serviceDescriptor.port() > 0,
                "serviceDescriptor port must be positive (was %s)", serviceDescriptor.port());

        var id = PostgresLeaderLatch.leaderLatchId(
                serviceDescriptor.name(),
                serviceDescriptor.version(),
                serviceDescriptor.hostname(),
                serviceDescriptor.port());

        return newLatch(connectionSupplier, configuration, serviceDescriptor.name(), id, listeners);
    }

    private static LeaderLatch newLatch(Supplier<Connection> connectionSupplier,
                                        LeaderLatchConfiguration configuration,
                                        String serviceName,
                                        String id,
                                        LeaderLatchListener... listeners) {
        requireNotNull(listeners, "listeners must not be null");

        var latch = new PostgresLeaderLatch(connectionSupplier, configuration, serviceName, id);
        Arrays.stream(listeners).forEach(latch::addListener);
        return latch;
    }

    /**
     * The wrapped latch. This is an "escape hatch" for anything not exposed by this class.
     *
     * @return the wrapped latch
     */
    public LeaderLatch getLatch() {
        return latch;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", getId())
                .add("leadershipKey", getLeadershipKey())
                .toString();
    }

    /**
     * Starts the latch. This returns without waiting to become the leader. Starting an already started latch
     * has no effect.
     *
     * @throws ManagedLeaderLatchException if the latch cannot be started, including when it was already closed
     */
    @Override
    public void start() {
        var result = latch.start();

        if (result instanceof StartResult.Failed failed) {
            throw new ManagedLeaderLatchException("Error starting leader latch " + getId(), failed.cause());
        }

        if (result instanceof StartResult.Closed) {
            throw new ManagedLeaderLatchException(
                    "Cannot start leader latch " + getId() + " because it has been closed");
        }

        LOG.trace("Start result for leader latch {}: {}", getId(), result);
    }

    /**
     * Stops (closes) the latch, releasing the lock if this latch is the leader. This never throws.
     */
    @Override
    public void stop() {
        LOG.trace("Stopping leader latch {}", getId());
        latch.close();
    }

    /**
     * The ID of this participant.
     *
     * @return the participant ID
     */
    public String getId() {
        return latch.getId();
    }

    /**
     * The key shared by all participants that contend for the same leadership.
     *
     * @return the leadership key
     */
    public String getLeadershipKey() {
        return latch.getLeadershipKey();
    }

    /**
     * Returns whether this participant can currently prove it is the leader. Never throws.
     *
     * @return true only if the latch is started and holds the lock
     * @see #checkLeadershipStatus()
     */
    public boolean hasLeadership() {
        return latch.hasLeadership();
    }

    /**
     * The negation of {@link #hasLeadership()}, useful for exiting a method early when not the leader.
     *
     * @return true if this participant is not provably the leader
     */
    public boolean doesNotHaveLeadership() {
        return latch.doesNotHaveLeadership();
    }

    /**
     * Checks leadership, returning a status that callers can switch over.
     *
     * @return the current leadership status
     */
    public LeadershipStatus checkLeadershipStatus() {
        return latch.checkLeadershipStatus();
    }

    /**
     * Looks up which participant holds the leadership lock. This queries Postgres.
     *
     * @return the outcome of the lookup
     */
    public LeaderInfo getLeader() {
        return latch.getLeader();
    }

    /**
     * Check whether the latch has been started and not closed.
     *
     * @return true if the latch is started
     */
    public boolean isStarted() {
        var status = latch.checkLeadershipStatus();
        return !(status instanceof LeadershipStatus.NotStarted) && !(status instanceof LeadershipStatus.Closed);
    }

    /**
     * Check whether the latch has been closed.
     *
     * @return true if the latch is closed
     */
    public boolean isClosed() {
        return latch.checkLeadershipStatus() instanceof LeadershipStatus.Closed;
    }

    /**
     * Run the action synchronously only if this latch is the leader.
     *
     * @param action the action
     * @return the outcome; never throws, even if the action does
     */
    public WhenLeaderResult<Void> whenLeader(Runnable action) {
        return latch.whenLeader(action);
    }

    /**
     * Run the action synchronously only if this latch is the leader.
     *
     * @param action the action
     * @param <T>    the result type
     * @return the outcome; never throws, even if the action does
     */
    public <T> WhenLeaderResult<T> whenLeader(Supplier<T> action) {
        return latch.whenLeader(action);
    }

    /**
     * Run the action asynchronously only if this latch is the leader.
     *
     * @param action the action
     * @return a future that completes with the outcome
     */
    public CompletableFuture<WhenLeaderResult<Void>> whenLeaderAsync(Runnable action) {
        return latch.whenLeaderAsync(action);
    }

    /**
     * Run the action asynchronously only if this latch is the leader.
     *
     * @param action the action
     * @param <T>    the result type
     * @return a future that completes with the outcome
     */
    public <T> CompletableFuture<WhenLeaderResult<T>> whenLeaderAsync(Supplier<T> action) {
        return latch.whenLeaderAsync(action);
    }

    /**
     * Run the action asynchronously on the given executor only if this latch is the leader.
     *
     * @param action   the action
     * @param executor the executor
     * @param <T>      the result type
     * @return a future that completes with the outcome
     */
    public <T> CompletableFuture<WhenLeaderResult<T>> whenLeaderAsync(Supplier<T> action, Executor executor) {
        return latch.whenLeaderAsync(action, executor);
    }
}
