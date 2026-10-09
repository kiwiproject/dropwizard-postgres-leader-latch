package org.kiwiproject.postgres.leader.dropwizard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kiwiproject.postgres.leader.LeaderInfo;
import org.kiwiproject.postgres.leader.LeaderLatch;
import org.kiwiproject.postgres.leader.LeaderLatchConfiguration;
import org.kiwiproject.postgres.leader.LeaderLatchListener;
import org.kiwiproject.postgres.leader.LeadershipStatus;
import org.kiwiproject.postgres.leader.StartResult;
import org.kiwiproject.postgres.leader.dropwizard.exception.ManagedLeaderLatchException;

import java.sql.Connection;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@DisplayName("ManagedLeaderLatch")
class ManagedLeaderLatchTest {

    private LeaderLatch latch;
    private ManagedLeaderLatch managedLatch;

    @BeforeEach
    void setUp() {
        latch = mock(LeaderLatch.class);
        when(latch.getId()).thenReturn("test-service/1.0.0/host:8080");
        when(latch.getLeadershipKey()).thenReturn("test-service");
        managedLatch = new ManagedLeaderLatch(latch);
    }

    @Nested
    class Construction {

        @Test
        void shouldRejectNullLatch() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ManagedLeaderLatch(null))
                    .withMessage("latch must not be null");
        }

        @Test
        void shouldExposeWrappedLatch() {
            assertThat(managedLatch.getLatch()).isSameAs(latch);
        }

        @Test
        void shouldCreateLatchFromServiceDescriptor() {
            var descriptor = ServiceDescriptor.builder()
                    .name("order-service")
                    .version("2.3.4")
                    .hostname("host42")
                    .port(8042)
                    .build();
            Supplier<Connection> connectionSupplier = () -> {
                throw new IllegalStateException("no database in this test");
            };
            var configuration = LeaderLatchConfiguration.defaults();

            var created = new ManagedLeaderLatch(connectionSupplier, configuration, descriptor);

            assertAll(
                    () -> assertThat(created.getId()).isEqualTo("order-service/2.3.4/host42:8042"),
                    () -> assertThat(created.getLeadershipKey()).isEqualTo("order-service"),
                    () -> assertThat(created.checkLeadershipStatus()).isInstanceOf(LeadershipStatus.NotStarted.class)
            );
        }

        @Test
        void shouldCreateLatchFromIdAndServiceName() {
            Supplier<Connection> connectionSupplier = () -> {
                throw new IllegalStateException("no database in this test");
            };
            var configuration = LeaderLatchConfiguration.defaults();
            var listener = mock(LeaderLatchListener.class);

            var created = new ManagedLeaderLatch(connectionSupplier, configuration, "my-id", "my-service", listener);

            assertAll(
                    () -> assertThat(created.getId()).isEqualTo("my-id"),
                    () -> assertThat(created.getLeadershipKey()).isEqualTo("my-service")
            );
        }

        @Test
        void shouldRejectInvalidServiceDescriptors() {
            Supplier<Connection> connectionSupplier = () -> {
                throw new IllegalStateException("no database in this test");
            };
            var configuration = LeaderLatchConfiguration.defaults();
            var valid = ServiceDescriptor.builder().name("svc").version("1.0").hostname("host").port(8080).build();

            assertAll(
                    () -> assertThatIllegalArgumentException()
                            .isThrownBy(() -> new ManagedLeaderLatch(connectionSupplier, configuration,
                                    ServiceDescriptor.builder().version("1.0").hostname("host").port(8080).build()))
                            .withMessage("serviceDescriptor name must not be blank"),
                    () -> assertThatIllegalArgumentException()
                            .isThrownBy(() -> new ManagedLeaderLatch(connectionSupplier, configuration,
                                    ServiceDescriptor.builder().name("svc").hostname("host").port(8080).build()))
                            .withMessage("serviceDescriptor version must not be blank"),
                    () -> assertThatIllegalArgumentException()
                            .isThrownBy(() -> new ManagedLeaderLatch(connectionSupplier, configuration,
                                    ServiceDescriptor.builder().name("svc").version("1.0").hostname(" ").port(8080).build()))
                            .withMessage("serviceDescriptor hostname must not be blank"),
                    () -> assertThatIllegalArgumentException()
                            .isThrownBy(() -> new ManagedLeaderLatch(connectionSupplier, configuration,
                                    ServiceDescriptor.builder().name("svc").version("1.0").hostname("host").build()))
                            .withMessage("serviceDescriptor port must be positive (was 0)"),
                    () -> assertThat(new ManagedLeaderLatch(connectionSupplier, configuration, valid).getId())
                            .isEqualTo("svc/1.0/host:8080")
            );
        }

        @Test
        void shouldRejectNullServiceDescriptor() {
            Supplier<Connection> connectionSupplier = () -> {
                throw new IllegalStateException("no database in this test");
            };
            var configuration = LeaderLatchConfiguration.defaults();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ManagedLeaderLatch(connectionSupplier, configuration, null))
                    .withMessage("serviceDescriptor must not be null");
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void shouldStartTheLatch() {
            when(latch.start()).thenReturn(new StartResult.Started());

            assertThatCode(managedLatch::start).doesNotThrowAnyException();

            verify(latch).start();
        }

        @Test
        void shouldIgnoreAlreadyStartedLatch() {
            when(latch.start()).thenReturn(new StartResult.AlreadyStarted());

            assertThatCode(managedLatch::start).doesNotThrowAnyException();
        }

        @Test
        void shouldThrowWhenTheLatchCannotBeStarted() {
            var cause = new IllegalStateException("boom");
            when(latch.start()).thenReturn(new StartResult.Failed(cause));

            assertThatThrownBy(managedLatch::start)
                    .isExactlyInstanceOf(ManagedLeaderLatchException.class)
                    .hasMessage("Error starting leader latch test-service/1.0.0/host:8080")
                    .hasCause(cause);
        }

        @Test
        void shouldThrowWhenTheLatchHasAlreadyBeenClosed() {
            when(latch.start()).thenReturn(new StartResult.Closed());

            assertThatThrownBy(managedLatch::start)
                    .isExactlyInstanceOf(ManagedLeaderLatchException.class)
                    .hasMessage("Cannot start leader latch test-service/1.0.0/host:8080 because it has been closed");
        }

        @Test
        void shouldCloseTheLatchOnStop() {
            managedLatch.stop();

            verify(latch).close();
        }
    }

    @Nested
    class Status {

        @Test
        void shouldDelegateLeadershipChecks() {
            when(latch.hasLeadership()).thenReturn(true);
            when(latch.doesNotHaveLeadership()).thenReturn(false);
            var status = new LeadershipStatus.IsLeader();
            when(latch.checkLeadershipStatus()).thenReturn(status);
            var leaderInfo = new LeaderInfo.NoLeader();
            when(latch.getLeader()).thenReturn(leaderInfo);

            assertAll(
                    () -> assertThat(managedLatch.hasLeadership()).isTrue(),
                    () -> assertThat(managedLatch.doesNotHaveLeadership()).isFalse(),
                    () -> assertThat(managedLatch.checkLeadershipStatus()).isSameAs(status),
                    () -> assertThat(managedLatch.getLeader()).isSameAs(leaderInfo)
            );
        }

        @Test
        void shouldBeStartedWhenLeaderOrFollower() {
            when(latch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.NotLeader());

            assertAll(
                    () -> assertThat(managedLatch.isStarted()).isTrue(),
                    () -> assertThat(managedLatch.isClosed()).isFalse()
            );
        }

        @Test
        void shouldNotBeStartedWhenNotStarted() {
            when(latch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.NotStarted());

            assertAll(
                    () -> assertThat(managedLatch.isStarted()).isFalse(),
                    () -> assertThat(managedLatch.isClosed()).isFalse()
            );
        }

        @Test
        void shouldBeClosedWhenClosed() {
            when(latch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.Closed());

            assertAll(
                    () -> assertThat(managedLatch.isStarted()).isFalse(),
                    () -> assertThat(managedLatch.isClosed()).isTrue()
            );
        }
    }

    @Nested
    class WhenLeader {

        @Test
        void shouldDelegateToTheLatch() {
            Runnable runnable = () -> { };
            Supplier<String> supplier = () -> "value";
            Executor executor = Runnable::run;

            managedLatch.whenLeader(runnable);
            managedLatch.whenLeader(supplier);
            managedLatch.whenLeaderAsync(runnable);
            managedLatch.whenLeaderAsync(supplier);
            managedLatch.whenLeaderAsync(supplier, executor);

            assertAll(
                    () -> verify(latch).whenLeader(runnable),
                    () -> verify(latch).whenLeader(supplier),
                    () -> verify(latch).whenLeaderAsync(runnable),
                    () -> verify(latch).whenLeaderAsync(supplier),
                    () -> verify(latch).whenLeaderAsync(supplier, executor)
            );
        }
    }

    @Test
    void shouldIncludeIdAndKeyInToString() {
        assertThat(managedLatch).hasToString(
                "ManagedLeaderLatch{id=test-service/1.0.0/host:8080, leadershipKey=test-service}");
    }
}
