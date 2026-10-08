package org.kiwiproject.postgres.leader.dropwizard.exception;

/**
 * Exception that is thrown when a managed leader latch cannot be started.
 * <p>
 * This is the only exception this library throws. Problems after startup, such as Postgres being unavailable,
 * are reported as values by the underlying leader latch.
 */
public class ManagedLeaderLatchException extends RuntimeException {

    /**
     * Create an exception with no message or cause.
     */
    public ManagedLeaderLatchException() {
    }

    /**
     * Create an exception with the given message.
     *
     * @param message the message
     */
    public ManagedLeaderLatchException(String message) {
        super(message);
    }

    /**
     * Create an exception with the given message and cause.
     *
     * @param message the message
     * @param cause   the cause
     */
    public ManagedLeaderLatchException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Create an exception with the given cause.
     *
     * @param cause the cause
     */
    public ManagedLeaderLatchException(Throwable cause) {
        super(cause);
    }
}
