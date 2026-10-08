package org.kiwiproject.postgres.leader.dropwizard.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ManagedLeaderLatchException")
class ManagedLeaderLatchExceptionTest {

    @Test
    void shouldCreateWithNoArguments() {
        var exception = new ManagedLeaderLatchException();

        assertAll(
                () -> assertThat(exception).hasMessage(null),
                () -> assertThat(exception).hasNoCause()
        );
    }

    @Test
    void shouldCreateWithMessage() {
        var exception = new ManagedLeaderLatchException("oops");

        assertAll(
                () -> assertThat(exception).hasMessage("oops"),
                () -> assertThat(exception).hasNoCause()
        );
    }

    @Test
    void shouldCreateWithMessageAndCause() {
        var cause = new IllegalStateException("cause");
        var exception = new ManagedLeaderLatchException("oops", cause);

        assertAll(
                () -> assertThat(exception).hasMessage("oops"),
                () -> assertThat(exception).hasCause(cause)
        );
    }

    @Test
    void shouldCreateWithCause() {
        var cause = new IllegalStateException("cause");
        var exception = new ManagedLeaderLatchException(cause);

        assertAll(
                () -> assertThat(exception).hasMessage("java.lang.IllegalStateException: cause"),
                () -> assertThat(exception).hasCause(cause)
        );
    }
}
