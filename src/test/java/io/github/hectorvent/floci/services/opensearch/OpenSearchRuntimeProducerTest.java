package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenSearchRuntimeProducerTest {

    @Test
    void rejectsInvalidExecutor() {
        var config = mock(EmulatorConfig.class);
        var services = mock(EmulatorConfig.ServicesConfig.class);
        var opensearch = mock(EmulatorConfig.OpenSearchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.opensearch()).thenReturn(opensearch);
        when(opensearch.executor()).thenReturn("containerd");

        assertThatThrownBy(() -> OpenSearchRuntimeProducer.requireValidExecutor(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown floci.services.opensearch.executor 'containerd'. "
                        + "Valid values: docker, kubernetes");
    }
}
