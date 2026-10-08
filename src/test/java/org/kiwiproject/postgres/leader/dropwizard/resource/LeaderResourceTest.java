package org.kiwiproject.postgres.leader.dropwizard.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.kiwiproject.jaxrs.KiwiGenericTypes.MAP_OF_STRING_TO_OBJECT_GENERIC_TYPE;
import static org.kiwiproject.test.jaxrs.JaxrsTestHelper.assertOkResponse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kiwiproject.postgres.leader.LeaderInfo;
import org.kiwiproject.postgres.leader.LeadershipStatus;
import org.kiwiproject.postgres.leader.dropwizard.ManagedLeaderLatch;

import java.time.Instant;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("LeaderResource")
class LeaderResourceTest {

    private static final ManagedLeaderLatch LEADER_LATCH = mock(ManagedLeaderLatch.class);

    private static final ResourceExtension RESOURCE = ResourceExtension.builder()
            .bootstrapLogging(false)
            .addResource(new LeaderResource(LEADER_LATCH))
            .build();

    @AfterEach
    void tearDown() {
        reset(LEADER_LATCH);
    }

    @Test
    void shouldRejectNullLatch() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LeaderResource(null))
                .withMessage("leaderLatch must not be null");
    }

    @ParameterizedTest(name = "[{index}] (isLeader = {argumentsWithNames})")
    @ValueSource(booleans = {true, false})
    void shouldReportLeadership(boolean isLeader) {
        when(LEADER_LATCH.hasLeadership()).thenReturn(isLeader);

        var response = RESOURCE.client()
                .target("/kiwi/leader-latch/leader")
                .request()
                .get();
        assertOkResponse(response);

        var entity = response.readEntity(MAP_OF_STRING_TO_OBJECT_GENERIC_TYPE);
        assertThat(entity).containsOnly(entry("leader", isLeader));

        verify(LEADER_LATCH).hasLeadership();
    }

    @Test
    void shouldReportLatchStateWhenThisParticipantIsLeader() {
        when(LEADER_LATCH.getId()).thenReturn("test-id-1");
        when(LEADER_LATCH.getLeadershipKey()).thenReturn("test-service");
        when(LEADER_LATCH.getLeader()).thenReturn(new LeaderInfo.Leader("test-id-1", Instant.now()));
        when(LEADER_LATCH.checkLeadershipStatus()).thenReturn(new LeadershipStatus.IsLeader());

        var response = RESOURCE.client()
                .target("/kiwi/leader-latch/latch")
                .request()
                .get();
        assertOkResponse(response);

        var entity = response.readEntity(MAP_OF_STRING_TO_OBJECT_GENERIC_TYPE);
        assertThat(entity)
                .containsOnly(
                        entry("id", "test-id-1"),
                        entry("leader", true),
                        entry("leadershipKey", "test-service"),
                        entry("leaderId", "test-id-1"),
                        entry("status", "IsLeader"));
    }

    @Test
    void shouldReportLatchStateWhenAnotherParticipantIsLeader() {
        when(LEADER_LATCH.getId()).thenReturn("test-id-1");
        when(LEADER_LATCH.getLeadershipKey()).thenReturn("test-service");
        when(LEADER_LATCH.getLeader()).thenReturn(new LeaderInfo.Leader("test-id-2", Instant.now()));
        when(LEADER_LATCH.checkLeadershipStatus()).thenReturn(new LeadershipStatus.NotLeader());

        var response = RESOURCE.client()
                .target("/kiwi/leader-latch/latch")
                .request()
                .get();
        assertOkResponse(response);

        var entity = response.readEntity(MAP_OF_STRING_TO_OBJECT_GENERIC_TYPE);
        assertAll(
                () -> assertThat(entity).containsEntry("leader", false),
                () -> assertThat(entity).containsEntry("leaderId", "test-id-2"),
                () -> assertThat(entity).containsEntry("status", "NotLeader")
        );
    }

    @Test
    void shouldReportNullLeaderIdWhenThereIsNoLeaderOrLookupFails() {
        when(LEADER_LATCH.getId()).thenReturn("test-id-1");
        when(LEADER_LATCH.getLeadershipKey()).thenReturn("test-service");
        when(LEADER_LATCH.getLeader()).thenReturn(new LeaderInfo.LookupFailed(new IllegalStateException("boom")));
        when(LEADER_LATCH.checkLeadershipStatus()).thenReturn(new LeadershipStatus.NotStarted());

        var response = RESOURCE.client()
                .target("/kiwi/leader-latch/latch")
                .request()
                .get();
        assertOkResponse(response);

        var entity = response.readEntity(MAP_OF_STRING_TO_OBJECT_GENERIC_TYPE);
        assertAll(
                () -> assertThat(entity).containsKey("leaderId"),
                () -> assertThat(entity.get("leaderId")).isNull(),
                () -> assertThat(entity).containsEntry("status", "NotStarted")
        );
    }
}
