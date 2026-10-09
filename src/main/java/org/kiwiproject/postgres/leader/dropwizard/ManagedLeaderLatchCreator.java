package org.kiwiproject.postgres.leader.dropwizard;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.Objects.nonNull;
import static org.kiwiproject.base.KiwiPreconditions.requireNotNull;

import io.dropwizard.core.setup.Environment;
import lombok.extern.slf4j.Slf4j;
import org.kiwiproject.postgres.leader.LeaderLatchConfiguration;
import org.kiwiproject.postgres.leader.LeaderLatchListener;
import org.kiwiproject.postgres.leader.dropwizard.exception.ManagedLeaderLatchException;
import org.kiwiproject.postgres.leader.dropwizard.health.ManagedLeaderLatchHealthCheck;
import org.kiwiproject.postgres.leader.dropwizard.resource.GotLeaderLatchResource;
import org.kiwiproject.postgres.leader.dropwizard.resource.LeaderResource;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Entry point to initialize a {@link ManagedLeaderLatch}, which wraps a Postgres-backed leader latch and allows
 * easy determination of whether a JVM process is the leader in a group of JVMs that use the same Postgres database.
 * <p>
 * The {@link ManagedLeaderLatch} created by this class will be started immediately, but is a Dropwizard
 * {@link io.dropwizard.lifecycle.Managed} so that it will be stopped when the Dropwizard service shuts down.
 * <p>
 * In addition, by default a {@link ManagedLeaderLatchHealthCheck} is registered with Dropwizard. Two REST resources
 * are registered, {@link GotLeaderLatchResource} and {@link LeaderResource}.
 * <p>
 * The latch uses one dedicated connection created from the {@link Supplier} the caller provides, and closes every
 * connection it obtains. Do not return connections from your application's connection pool.
 */
@Slf4j
public class ManagedLeaderLatchCreator {

    // Initialized at construction
    private final Supplier<Connection> connectionSupplier;
    private final LeaderLatchConfiguration configuration;
    private final Environment environment;
    private final ServiceDescriptor serviceDescriptor;
    private final List<LeaderLatchListener> listeners;

    // Initialized via instance start() method so cannot be final
    private ManagedLeaderLatch leaderLatch;
    private List<LeaderLatchListener> startedListeners;
    private boolean latchStarted;
    private boolean addHealthCheck;
    private ManagedLeaderLatchHealthCheck healthCheck;
    private boolean addResources;

    private ManagedLeaderLatchCreator(Supplier<Connection> connectionSupplier,
                                      LeaderLatchConfiguration configuration,
                                      Environment environment,
                                      ServiceDescriptor serviceDescriptor,
                                      List<LeaderLatchListener> listeners) {

        this.connectionSupplier = requireNotNull(connectionSupplier, "connectionSupplier must not be null");
        this.configuration = requireNotNull(configuration, "configuration must not be null");
        this.environment = requireNotNull(environment, "environment must not be null");
        this.serviceDescriptor = requireNotNull(serviceDescriptor, "serviceDescriptor must not be null");
        this.listeners = requireNotNull(listeners, "listeners must not be null");
        this.addHealthCheck = true;
        this.addResources = true;
    }

    /**
     * Static factory method to create a {@link ManagedLeaderLatchCreator}.
     * <p>
     * Use this method when you want to perform additional configuration before starting the leader latch.
     * <p>
     * You will need to call {@link #start()} on the returned instance to start the {@link ManagedLeaderLatch}.
     *
     * @param connectionSupplier    the AWS SDK client; it is not closed by the latch
     * @param configuration     the leader latch configuration
     * @param environment       the Dropwizard environment
     * @param serviceDescriptor service metadata
     * @param listeners         optional listeners
     * @return a new instance
     * @throws IllegalArgumentException if any required arguments are null
     */
    public static ManagedLeaderLatchCreator from(Supplier<Connection> connectionSupplier,
                                                 LeaderLatchConfiguration configuration,
                                                 Environment environment,
                                                 ServiceDescriptor serviceDescriptor,
                                                 LeaderLatchListener... listeners) {

        // The list of listeners must be mutable or else exceptions will be thrown if
        // addLeaderLatchListener is called subsequently (since it adds to the existing list).

        requireNotNull(listeners, "listeners must not be null");
        return new ManagedLeaderLatchCreator(connectionSupplier, configuration, environment, serviceDescriptor,
                newArrayList(listeners));
    }

    /**
     * If the only thing you want is a {@link ManagedLeaderLatch} and you want the standard options (a health
     * check and Jakarta REST resources) and you do not need references to them, use this method to create and
     * start a latch.
     * <p>
     * Otherwise, use {@link #start(Supplier, LeaderLatchConfiguration, Environment, ServiceDescriptor, LeaderLatchListener...)}.
     *
     * @param connectionSupplier    the AWS SDK client; it is not closed by the latch
     * @param configuration     the leader latch configuration
     * @param environment       the Dropwizard environment
     * @param serviceDescriptor service metadata
     * @param listeners         optional listeners
     * @return a started {@link ManagedLeaderLatch}
     * @throws IllegalArgumentException    if any required arguments are null
     * @throws ManagedLeaderLatchException if the latch cannot be started
     */
    public static ManagedLeaderLatch startLeaderLatch(Supplier<Connection> connectionSupplier,
                                                      LeaderLatchConfiguration configuration,
                                                      Environment environment,
                                                      ServiceDescriptor serviceDescriptor,
                                                      LeaderLatchListener... listeners) {

        return start(connectionSupplier, configuration, environment, serviceDescriptor, listeners).getLeaderLatch();
    }

    /**
     * If you want a {@link ManagedLeaderLatch} and you want the standard options (a health
     * check and Jakarta REST resources) and you might need references to them, use this method to create and
     * start a latch.
     * <p>
     * The returned {@link ManagedLeaderLatchCreator} can be used to then obtain the {@link ManagedLeaderLatch}
     * as well as the health check and listeners.
     *
     * @param connectionSupplier    the AWS SDK client; it is not closed by the latch
     * @param configuration     the leader latch configuration
     * @param environment       the Dropwizard environment
     * @param serviceDescriptor service metadata
     * @param listeners         optional listeners
     * @return a {@link ManagedLeaderLatchCreator} with a started {@link ManagedLeaderLatch}
     * @throws IllegalArgumentException    if any required arguments are null
     * @throws ManagedLeaderLatchException if the latch cannot be started
     */
    public static ManagedLeaderLatchCreator start(Supplier<Connection> connectionSupplier,
                                                  LeaderLatchConfiguration configuration,
                                                  Environment environment,
                                                  ServiceDescriptor serviceDescriptor,
                                                  LeaderLatchListener... listeners) {

        return from(connectionSupplier, configuration, environment, serviceDescriptor, listeners).start();
    }

    /**
     * Configures <em>without</em> a health check.
     * <p>
     * Use only when constructing a new {@link ManagedLeaderLatchCreator}.
     *
     * @return this instance, for method chaining
     */
    public ManagedLeaderLatchCreator withoutHealthCheck() {
        addHealthCheck = false;
        return this;
    }

    /**
     * Configures <em>without</em> REST resources to check for leadership and if a leader latch is present.
     * <p>
     * Use only when constructing a new {@link ManagedLeaderLatchCreator}.
     *
     * @return this instance, for method chaining
     */
    public ManagedLeaderLatchCreator withoutResources() {
        addResources = false;
        return this;
    }

    /**
     * Adds the specified {@link LeaderLatchListener}.
     * <p>
     * Use only when constructing a new {@link ManagedLeaderLatchCreator}, before calling {@link #start()}.
     *
     * @param listener the listener to add
     * @return this instance, for method chaining
     */
    public ManagedLeaderLatchCreator addLeaderLatchListener(LeaderLatchListener listener) {
        listeners.add(requireNotNull(listener, "listener must not be null"));
        return this;
    }

    /**
     * Starts the leader latch, performing the following actions:
     * <ul>
     * <li>
     *     Creates a new {@link ManagedLeaderLatch}, tells the Dropwizard lifecycle to manage (stop) it, and starts it
     * </li>
     * <li>
     *     Creates and registers a {@link ManagedLeaderLatchHealthCheck} unless explicitly disabled via
     *     {@link #withoutHealthCheck()}
     * </li>
     * <li>
     *     Creates and registers the Jakarta REST endpoints unless explicitly disabled via {@link #withoutResources()}
     * </li>
     * </ul>
     * <p>
     * Starting the latch does not wait to become the leader. Note that once this method is called, nothing about
     * the {@link ManagedLeaderLatch} can be changed, and calls to other instance methods (e.g.,
     * addLeaderLatchListener) will have no effect. Similarly, calling this method more than once is considered
     * unexpected behavior, and we will simply return the existing instance without taking any other actions.
     *
     * @return this instance, from which you can then retrieve the (started) {@link ManagedLeaderLatch}
     * @throws ManagedLeaderLatchException if the latch cannot be started
     */
    public ManagedLeaderLatchCreator start() {
        if (nonNull(leaderLatch)) {
            LOG.warn("start() has already been called. Ignoring this invocation.");
            return this;
        }

        var listenerArray = listeners.toArray(new LeaderLatchListener[0]);
        var newLatch = new ManagedLeaderLatch(connectionSupplier, configuration, serviceDescriptor, listenerArray);

        // Start first, so a latch that cannot start is never registered with Dropwizard. Then register it
        // before anything else that can fail, so Dropwizard stops it if the rest of startup fails.
        newLatch.start();
        environment.lifecycle().manage(newLatch);

        leaderLatch = newLatch;
        startedListeners = List.copyOf(listeners);
        latchStarted = true;
        addHealthCheckIfConfigured();
        addResourcesIfConfigured();

        return this;
    }

    private void addHealthCheckIfConfigured() {
        if (addHealthCheck) {
            healthCheck = new ManagedLeaderLatchHealthCheck(leaderLatch);
            environment.healthChecks().register("leaderLatch", healthCheck);
        }
    }

    private void addResourcesIfConfigured() {
        if (addResources) {
            environment.jersey().register(new GotLeaderLatchResource());
            environment.jersey().register(new LeaderResource(leaderLatch));
        }
    }

    /**
     * Has this instance created and started a {@link ManagedLeaderLatch}?
     *
     * @return true if this instance has created and started a leader latch
     */
    public boolean isLeaderLatchStarted() {
        return latchStarted;
    }

    /**
     * Returns the {@link ManagedLeaderLatch} created after {@link #start()} has been called.
     * <p>
     * Use {@link #isLeaderLatchStarted()} to ensure the latch has been started to ensure this method will succeed.
     *
     * @return the leader latch
     * @throws IllegalStateException if called but the latch has not been started yet
     */
    public ManagedLeaderLatch getLeaderLatch() {
        validateStarted();
        return leaderLatch;
    }

    /**
     * Returns the health check (if registered) after {@link #start()} has been called.
     *
     * @return the registered health check
     * @throws IllegalStateException if called but the latch has not been started yet
     */
    public Optional<ManagedLeaderLatchHealthCheck> getHealthCheck() {
        validateStarted();
        return Optional.ofNullable(healthCheck);
    }

    /**
     * Returns a list containing all registered {@link LeaderLatchListener}s after {@link #start()} has been called.
     *
     * @return any registered {@link LeaderLatchListener}s
     * @throws IllegalStateException if called but the latch has not been started yet
     * @implNote The returned list is an unmodifiable snapshot of the listeners the latch was started with
     */
    public List<LeaderLatchListener> getListeners() {
        validateStarted();
        // already immutable, so this returns the same instance; the call makes the immutability explicit
        return List.copyOf(startedListeners);
    }

    private void validateStarted() {
        checkState(latchStarted, "Leader latch is not started; call start() first");
    }
}
