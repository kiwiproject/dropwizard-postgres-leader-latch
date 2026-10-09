package org.kiwiproject.postgres.leader.dropwizard;

import lombok.Builder;

/**
 * Metadata about a service that participates in a leader latch.
 * <p>
 * The {@code name} is the leadership key shared by all instances of the same logical service, and the
 * name, version, hostname, and port together identify one participant.
 *
 * @param name     the service name, used as the leadership key
 * @param version  the service version
 * @param hostname the hostname of this instance
 * @param port     the port of this instance
 */
@Builder
public record ServiceDescriptor(String name, String version, String hostname, int port) {
}
