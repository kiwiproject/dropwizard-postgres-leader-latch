package org.kiwiproject.postgres.leader.dropwizard.resource;

import static org.kiwiproject.base.KiwiPreconditions.requireNotNull;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.kiwiproject.postgres.leader.LeaderInfo;
import org.kiwiproject.postgres.leader.dropwizard.ManagedLeaderLatch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jakarta REST (formerly JAX-RS) resource providing endpoints for checking leadership and latch information.
 */
@Path("/kiwi/leader-latch")
@Produces(MediaType.APPLICATION_JSON)
public class LeaderResource {

    private final ManagedLeaderLatch leaderLatch;

    /**
     * Create a resource for the given latch.
     *
     * @param leaderLatch the latch to report on
     * @throws IllegalArgumentException if the latch is null
     */
    public LeaderResource(ManagedLeaderLatch leaderLatch) {
        this.leaderLatch = requireNotNull(leaderLatch, "leaderLatch must not be null");
    }

    /**
     * Checks whether this service is the latch leader.
     *
     * @return the JSON {@link Response}
     */
    @GET
    @Path("/leader")
    public Response hasLeadership() {
        var entity = Map.of(
                "leader", leaderLatch.hasLeadership()
        );
        return Response.ok(entity).build();
    }

    /**
     * Get information about the leader latch that this service participates in.
     * <p>
     * The response contains:
     * <ul>
     *     <li>{@code id}: the ID of this participant</li>
     *     <li>{@code leader}: whether this participant is the leader, taken from the same status snapshot as
     *     {@code status} so the two always agree</li>
     *     <li>{@code leadershipKey}: the key shared by all participants contending for the same leadership</li>
     *     <li>{@code leaderId}: the participant ID of the session holding the leadership lock, or null if there is
     *     no leader or the lookup failed</li>
     *     <li>{@code status}: the leadership status of this participant, e.g. {@code IsLeader} or {@code NotLeader}</li>
     * </ul>
     * This queries Postgres.
     *
     * @return the JSON {@link Response}
     */
    @GET
    @Path("/latch")
    public Response getLatchState() {
        var status = leaderLatch.checkLeadershipStatus();
        var leaderId = leaderLatch.getLeader() instanceof LeaderInfo.Leader leader ? leader.participantId() : null;

        // Map.of does not allow null values, and leaderId can be null
        var entity = new LinkedHashMap<String, Object>();
        entity.put("id", leaderLatch.getId());
        entity.put("leader", status.isLeader());
        entity.put("leadershipKey", leaderLatch.getLeadershipKey());
        entity.put("leaderId", leaderId);
        entity.put("status", status.getClass().getSimpleName());

        return Response.ok(entity).build();
    }
}
