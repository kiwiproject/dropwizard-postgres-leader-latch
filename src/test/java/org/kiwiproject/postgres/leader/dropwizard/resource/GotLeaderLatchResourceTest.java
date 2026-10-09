package org.kiwiproject.postgres.leader.dropwizard.resource;

import static org.kiwiproject.test.dropwizard.resource.DropwizardResourceTests.resourceExtensionFor;
import static org.kiwiproject.test.jaxrs.JaxrsTestHelper.assertNoContentResponse;

import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("GotLeaderLatchResource")
class GotLeaderLatchResourceTest {

    private static final ResourceExtension RESOURCE = resourceExtensionFor(new GotLeaderLatchResource());

    @Test
    void shouldReturnNoContent() {
        var response = RESOURCE.target("/kiwi/got-leader-latch")
                .request()
                .get();

        assertNoContentResponse(response);
    }
}
