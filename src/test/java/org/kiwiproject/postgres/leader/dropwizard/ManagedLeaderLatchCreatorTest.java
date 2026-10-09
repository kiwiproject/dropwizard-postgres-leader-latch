package org.kiwiproject.postgres.leader.dropwizard;

import static java.util.Objects.nonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.kiwiproject.collect.KiwiLists.first;
import static org.kiwiproject.collect.KiwiLists.second;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.codahale.metrics.health.HealthCheckRegistry;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kiwiproject.postgres.leader.LeaderLatchConfiguration;
import org.kiwiproject.postgres.leader.LeaderLatchListener;
import org.kiwiproject.postgres.leader.LeadershipStatus;
import org.kiwiproject.postgres.leader.dropwizard.health.ManagedLeaderLatchHealthCheck;
import org.kiwiproject.postgres.leader.dropwizard.resource.GotLeaderLatchResource;
import org.kiwiproject.postgres.leader.dropwizard.resource.LeaderResource;
import org.kiwiproject.test.dropwizard.mockito.DropwizardMockitoMocks;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.util.function.Supplier;

@DisplayName("ManagedLeaderLatchCreator")
class ManagedLeaderLatchCreatorTest {

    private Supplier<Connection> connectionSupplier;
    private LeaderLatchConfiguration configuration;
    private Environment environment;
    private JerseyEnvironment jersey;
    private LifecycleEnvironment lifecycle;
    private HealthCheckRegistry healthCheckRegistry;
    private ServiceDescriptor serviceDescriptor;

    private ManagedLeaderLatchCreator latchCreator;

    @BeforeEach
    void setUp() {
        // The supplier cannot create a connection, so the latch started by the creator never acquires leadership
        connectionSupplier = () -> {
            throw new IllegalStateException("no database in this test");
        };
        configuration = LeaderLatchConfiguration.defaults();

        var dropwizardMockitoContext = DropwizardMockitoMocks.mockDropwizard();
        environment = dropwizardMockitoContext.environment();
        jersey = dropwizardMockitoContext.jersey();
        lifecycle = dropwizardMockitoContext.lifecycle();
        healthCheckRegistry = dropwizardMockitoContext.healthChecks();

        serviceDescriptor = ServiceDescriptor.builder()
                .name("test-service")
                .version("42.0.84")
                .hostname("host42")
                .port(8042)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (nonNull(latchCreator) && latchCreator.isLeaderLatchStarted()) {
            latchCreator.getLeaderLatch().stop();
        }
    }

    @Test
    void shouldRejectNullArguments() {
        assertAll(
                () -> assertThatIllegalArgumentException()
                        .isThrownBy(() -> ManagedLeaderLatchCreator.from(null, configuration, environment, serviceDescriptor))
                        .withMessage("connectionSupplier must not be null"),
                () -> assertThatIllegalArgumentException()
                        .isThrownBy(() -> ManagedLeaderLatchCreator.from(connectionSupplier, null, environment, serviceDescriptor))
                        .withMessage("configuration must not be null"),
                () -> assertThatIllegalArgumentException()
                        .isThrownBy(() -> ManagedLeaderLatchCreator.from(connectionSupplier, configuration, null, serviceDescriptor))
                        .withMessage("environment must not be null"),
                () -> assertThatIllegalArgumentException()
                        .isThrownBy(() -> ManagedLeaderLatchCreator.from(connectionSupplier, configuration, environment, null))
                        .withMessage("serviceDescriptor must not be null"),
                () -> assertThatIllegalArgumentException()
                        .isThrownBy(() -> ManagedLeaderLatchCreator.from(connectionSupplier, configuration, environment, serviceDescriptor, (LeaderLatchListener[]) null))
                        .withMessage("listeners must not be null")
        );
    }

    @Test
    void shouldRejectNullListener() {
        latchCreator = ManagedLeaderLatchCreator.from(connectionSupplier, configuration, environment, serviceDescriptor);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> latchCreator.addLeaderLatchListener(null))
                .withMessage("listener must not be null");
    }

    @Test
    void shouldThrowIllegalStateExceptions_FromGetMethods_WhenNotStarted() {
        latchCreator = ManagedLeaderLatchCreator.from(connectionSupplier, configuration, environment, serviceDescriptor);

        assertAll(
                () -> assertThat(latchCreator.isLeaderLatchStarted()).isFalse(),
                () -> assertThatIllegalStateException().isThrownBy(latchCreator::getLeaderLatch),
                () -> assertThatIllegalStateException().isThrownBy(latchCreator::getHealthCheck),
                () -> assertThatIllegalStateException().isThrownBy(latchCreator::getListeners)
        );
    }

    @Test
    void shouldCreateAndManageLeaderLatch() {
        latchCreator = ManagedLeaderLatchCreator
                .from(connectionSupplier, configuration, environment, serviceDescriptor)
                .start();

        assertAll(
                () -> assertThat(latchCreator.isLeaderLatchStarted()).isTrue(),
                () -> assertThat(latchCreator.getLeaderLatch()).isNotNull(),
                () -> assertThat(latchCreator.getListeners()).isEmpty()
        );

        var managedCaptor = ArgumentCaptor.forClass(Managed.class);
        verify(lifecycle).manage(managedCaptor.capture());
        var managed = first(managedCaptor.getAllValues());
        assertThat(managed).isExactlyInstanceOf(ManagedLeaderLatch.class);

        var managedLeaderLatch = (ManagedLeaderLatch) managed;
        assertAll(
                () -> assertThat(managedLeaderLatch).isSameAs(latchCreator.getLeaderLatch()),
                () -> assertThat(managedLeaderLatch.getId()).isEqualTo("test-service/42.0.84/host42:8042"),
                () -> assertThat(managedLeaderLatch.getLeadershipKey()).isEqualTo("test-service"),
                () -> assertThat(managedLeaderLatch.checkLeadershipStatus())
                        .isNotInstanceOfAny(LeadershipStatus.NotStarted.class, LeadershipStatus.Closed.class)
        );
    }

    @Test
    void shouldRegisterHealthCheckAndResources_ByDefault() {
        latchCreator = ManagedLeaderLatchCreator
                .from(connectionSupplier, configuration, environment, serviceDescriptor)
                .start();

        assertThat(latchCreator.getHealthCheck()).isPresent();

        verify(healthCheckRegistry).register(eq("leaderLatch"), isA(ManagedLeaderLatchHealthCheck.class));
        verify(jersey).register(isA(GotLeaderLatchResource.class));
        verify(jersey).register(isA(LeaderResource.class));
    }

    @Test
    void shouldNotRegisterHealthCheck_WhenConfiguredWithout() {
        latchCreator = ManagedLeaderLatchCreator
                .from(connectionSupplier, configuration, environment, serviceDescriptor)
                .withoutHealthCheck()
                .start();

        assertAll(
                () -> assertThat(latchCreator.getHealthCheck()).isEmpty(),
                () -> verifyNoInteractions(healthCheckRegistry),
                () -> verify(jersey, times(2)).register(any(Object.class))
        );
    }

    @Test
    void shouldNotRegisterResources_WhenConfiguredWithout() {
        latchCreator = ManagedLeaderLatchCreator
                .from(connectionSupplier, configuration, environment, serviceDescriptor)
                .withoutResources()
                .start();

        assertAll(
                () -> assertThat(latchCreator.getHealthCheck()).isPresent(),
                () -> verifyNoInteractions(jersey),
                () -> verify(healthCheckRegistry).register(anyString(), any())
        );
    }

    @Test
    void shouldIgnoreMultipleCallsToStart() {
        latchCreator = ManagedLeaderLatchCreator.from(connectionSupplier, configuration, environment, serviceDescriptor);

        var latch1 = latchCreator.start();
        var latch2 = latchCreator.start();
        var latch3 = latchCreator.start();

        assertThat(latch1).isSameAs(latch2).isSameAs(latch3);

        verify(lifecycle).manage(any(Managed.class));
        verify(jersey, times(2)).register(any(Object.class));
        verify(healthCheckRegistry).register(anyString(), any());

        verifyNoMoreInteractions(lifecycle, jersey, healthCheckRegistry);
    }

    @Test
    void shouldStartUsingStaticFactoryMethods() {
        var creator = ManagedLeaderLatchCreator.start(connectionSupplier, configuration, environment, serviceDescriptor);

        try {
            assertThat(creator.isLeaderLatchStarted()).isTrue();
        } finally {
            creator.getLeaderLatch().stop();
        }
    }

    @Test
    void shouldReturnStartedLatch_FromStartLeaderLatch() {
        var managedLatch = ManagedLeaderLatchCreator
                .startLeaderLatch(connectionSupplier, configuration, environment, serviceDescriptor);

        try {
            assertThat(managedLatch.getId()).isEqualTo("test-service/42.0.84/host42:8042");
        } finally {
            managedLatch.stop();
        }
    }

    @Test
    void shouldRegisterListeners_InOrder_UsingVarargs() {
        var noOpListener = new NoOpListener();
        latchCreator = ManagedLeaderLatchCreator
                .from(connectionSupplier, configuration, environment, serviceDescriptor, noOpListener)
                .start();

        assertThat(latchCreator.getListeners()).containsExactly(noOpListener);
    }

    @Test
    void shouldRegisterListeners_InOrder_UsingAddMethod() {
        var noOpListener = new NoOpListener();
        var otherListener = new NoOpListener();
        latchCreator = ManagedLeaderLatchCreator
                .from(connectionSupplier, configuration, environment, serviceDescriptor)
                .addLeaderLatchListener(noOpListener)
                .addLeaderLatchListener(otherListener)
                .start();

        assertAll(
                () -> assertThat(latchCreator.getListeners()).hasSize(2),
                () -> assertThat(first(latchCreator.getListeners())).isSameAs(noOpListener),
                () -> assertThat(second(latchCreator.getListeners())).isSameAs(otherListener)
        );
    }

    @Test
    void shouldNotIncludeListenersAddedAfterStart() {
        var noOpListener = new NoOpListener();
        latchCreator = ManagedLeaderLatchCreator
                .from(connectionSupplier, configuration, environment, serviceDescriptor, noOpListener)
                .start();

        latchCreator.addLeaderLatchListener(new NoOpListener());

        assertThat(latchCreator.getListeners()).containsExactly(noOpListener);
    }

    @Test
    void shouldNotManageOrRegisterAnything_WhenTheLatchCannotBeStarted() {
        var invalidDescriptor = ServiceDescriptor.builder().name("test-service").build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ManagedLeaderLatchCreator
                        .from(connectionSupplier, configuration, environment, invalidDescriptor)
                        .start());

        verifyNoInteractions(lifecycle, jersey, healthCheckRegistry);
    }

    @Test
    void shouldReturnImmutableCopyOfListeners() {
        var noOpListener = new NoOpListener();
        latchCreator = ManagedLeaderLatchCreator
                .from(connectionSupplier, configuration, environment, serviceDescriptor, noOpListener)
                .start();

        var listeners = latchCreator.getListeners();

        assertThatThrownBy(() -> listeners.add(noOpListener))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static class NoOpListener implements LeaderLatchListener {
        @Override
        public void isLeader() {
            // no-op
        }

        @Override
        public void notLeader() {
            // no-op
        }
    }
}
