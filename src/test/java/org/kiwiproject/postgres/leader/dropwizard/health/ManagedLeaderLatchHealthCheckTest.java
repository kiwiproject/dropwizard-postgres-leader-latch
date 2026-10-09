package org.kiwiproject.postgres.leader.dropwizard.health;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.kiwiproject.test.assertj.dropwizard.metrics.HealthCheckResultAssertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kiwiproject.postgres.leader.LeaderInfo;
import org.kiwiproject.postgres.leader.LeadershipStatus;
import org.kiwiproject.postgres.leader.dropwizard.ManagedLeaderLatch;

import java.time.Instant;

@DisplayName("ManagedLeaderLatchHealthCheck")
class ManagedLeaderLatchHealthCheckTest {

    private ManagedLeaderLatch leaderLatch;
    private ManagedLeaderLatchHealthCheck healthCheck;

    @BeforeEach
    void setUp() {
        leaderLatch = mock(ManagedLeaderLatch.class);
        when(leaderLatch.getId()).thenReturn("1");
        when(leaderLatch.getLeadershipKey()).thenReturn("test-service");
        healthCheck = new ManagedLeaderLatchHealthCheck(leaderLatch);
    }

    @Test
    void shouldRejectNullLatch() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ManagedLeaderLatchHealthCheck(null))
                .withMessage("leaderLatch must not be null");
    }

    @Nested
    class WhenLatchIsNotRunning {

        @Test
        void shouldBeUnhealthy_WhenNotStarted() {
            when(leaderLatch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.NotStarted());

            assertThat(healthCheck)
                    .isUnhealthy()
                    .hasMessage("Leader latch for key test-service is not started (status: NotStarted)")
                    .hasDetail("leader", false)
                    .hasDetail("leaderParticipant", null)
                    .hasDetail("thisParticipant", "1")
                    .hasDetail("severity", "CRITICAL");
        }

        @Test
        void shouldBeUnhealthy_WhenClosed() {
            when(leaderLatch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.Closed());

            assertThat(healthCheck)
                    .isUnhealthy()
                    .hasMessage("Leader latch for key test-service is not started (status: Closed)")
                    .hasDetail("leader", false)
                    .hasDetail("severity", "CRITICAL");
        }
    }

    @Nested
    class WhenLeaderCannotBeDetermined {

        @Test
        void shouldBeUnhealthy_WhenLookupFails() {
            when(leaderLatch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.NotLeader());
            when(leaderLatch.getLeader()).thenReturn(new LeaderInfo.LookupFailed(new IllegalStateException("boom")));

            assertThat(healthCheck)
                    .isUnhealthy()
                    .hasMessage("Unable to determine the leader for key test-service: java.lang.IllegalStateException: boom")
                    .hasDetail("leader", false)
                    .hasDetail("leaderParticipant", null)
                    .hasDetail("thisParticipant", "1")
                    .hasDetail("severity", "CRITICAL");
        }

        @Test
        void shouldBeUnhealthy_WhenThereIsNoLeader() {
            when(leaderLatch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.NotLeader());
            when(leaderLatch.getLeader()).thenReturn(new LeaderInfo.NoLeader());

            assertThat(healthCheck)
                    .isUnhealthy()
                    .hasMessage("There is NO leader for key test-service")
                    .hasDetail("leader", false)
                    .hasDetail("leaderParticipant", null)
                    .hasDetail("thisParticipant", "1")
                    .hasDetail("severity", "CRITICAL");
        }

        @Test
        void shouldIncludeTheReasonInTheMessage_WhenThereIsNoLeaderAndAcquisitionIsFailing() {
            var cause = new IllegalStateException("Connected to a standby server");
            when(leaderLatch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.Uncertain(cause));
            when(leaderLatch.getLeader()).thenReturn(new LeaderInfo.NoLeader());

            assertThat(healthCheck)
                    .isUnhealthy()
                    .hasMessage("There is NO leader for key test-service (this participant could not try to acquire"
                            + " leadership: java.lang.IllegalStateException: Connected to a standby server)")
                    .hasDetail("leader", false)
                    .hasDetail("severity", "CRITICAL");
        }
    }

    @Nested
    class WhenThisParticipantDisagreesWithPostgres {

        @Test
        void shouldBeUnhealthy_WhenThisParticipantThinksItIsLeaderButAnotherIsRecorded() {
            when(leaderLatch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.IsLeader());
            when(leaderLatch.getLeader()).thenReturn(new LeaderInfo.Leader("2", Instant.now()));

            assertThat(healthCheck)
                    .isUnhealthy()
                    .hasMessage("This participant (1) believes it is the leader for key test-service, but Postgres shows 2 holding the lock")
                    .hasDetail("leader", true)
                    .hasDetail("leaderParticipant", "2")
                    .hasDetail("thisParticipant", "1")
                    .hasDetail("severity", "CRITICAL");
        }
    }

    @Nested
    class WhenLeadershipChangesDuringTheCheck {

        @Test
        void shouldNotReportAMismatch_WhenThisParticipantStoppedBeingLeaderDuringTheLookup() {
            when(leaderLatch.checkLeadershipStatus())
                    .thenReturn(new LeadershipStatus.IsLeader())
                    .thenReturn(new LeadershipStatus.NotLeader());
            when(leaderLatch.getLeader()).thenReturn(new LeaderInfo.Leader("2", Instant.now()));

            assertThat(healthCheck)
                    .isHealthy()
                    .hasDetail("leader", false)
                    .hasDetail("leaderParticipant", "2")
                    .hasDetail("thisParticipant", "1");
        }
    }

    @Nested
    class WhenHealthy {

        @Test
        void shouldBeHealthy_WhenThisParticipantIsTheLeader() {
            when(leaderLatch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.IsLeader());
            when(leaderLatch.getLeader()).thenReturn(new LeaderInfo.Leader("1", Instant.now()));

            assertThat(healthCheck)
                    .isHealthy()
                    .hasMessage("Leader latch for key test-service is started (has leadership? true)")
                    .hasDetail("leader", true)
                    .hasDetail("leaderParticipant", "1")
                    .hasDetail("thisParticipant", "1")
                    .hasDetail("severity", "OK");
        }

        @Test
        void shouldBeHealthy_WhenAnotherParticipantIsTheLeader() {
            when(leaderLatch.checkLeadershipStatus()).thenReturn(new LeadershipStatus.NotLeader());
            when(leaderLatch.getLeader()).thenReturn(new LeaderInfo.Leader("2", Instant.now()));

            assertThat(healthCheck)
                    .isHealthy()
                    .hasMessage("Leader latch for key test-service is started (has leadership? false)")
                    .hasDetail("leader", false)
                    .hasDetail("leaderParticipant", "2")
                    .hasDetail("thisParticipant", "1")
                    .hasDetail("severity", "OK");
        }

        @Test
        void shouldBeHealthy_WhenStatusIsUncertainButTheLeaderIsKnown() {
            var uncertain = new LeadershipStatus.Uncertain(new IllegalStateException("cannot acquire"));
            when(leaderLatch.checkLeadershipStatus()).thenReturn(uncertain);
            when(leaderLatch.getLeader()).thenReturn(new LeaderInfo.Leader("2", Instant.now()));

            assertThat(healthCheck)
                    .isHealthy()
                    .hasDetail("leader", false)
                    .hasDetail("leaderParticipant", "2");
        }
    }
}
