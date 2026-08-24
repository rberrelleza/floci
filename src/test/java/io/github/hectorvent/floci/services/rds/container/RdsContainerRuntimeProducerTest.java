package io.github.hectorvent.floci.services.rds.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsContainerRuntimeProducerTest {

    @Test
    void selectsDockerWithoutResolvingKubernetes() {
        var config = config("docker");
        var docker = instance(mock(RdsContainerManager.class));
        var kubernetes = instance(mock(KubernetesRdsContainerManager.class));

        var selected = new RdsContainerRuntimeProducer().runtime(config, docker, kubernetes);

        assertEquals(docker.get(), selected);
        verify(kubernetes, never()).get();
    }

    @Test
    void selectsKubernetesWithoutResolvingDocker() {
        var config = config("KUBERNETES");
        var docker = instance(mock(RdsContainerManager.class));
        var kubernetes = instance(mock(KubernetesRdsContainerManager.class));

        var selected = new RdsContainerRuntimeProducer().runtime(config, docker, kubernetes);

        assertEquals(kubernetes.get(), selected);
        verify(docker, never()).get();
    }

    @Test
    void rejectsUnknownExecutor() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> RdsContainerRuntimeProducer.requireValidExecutor(config("containerd")));
        assertEquals(
                "Unknown floci.services.rds.executor 'containerd'. Valid values: docker, kubernetes",
                exception.getMessage());
    }

    private static EmulatorConfig config(String executor) {
        var config = mock(EmulatorConfig.class);
        var services = mock(EmulatorConfig.ServicesConfig.class);
        var rds = mock(EmulatorConfig.RdsServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.rds()).thenReturn(rds);
        when(rds.executor()).thenReturn(executor);
        return config;
    }

    private static <T> Instance<T> instance(T value) {
        var instance = mock(Instance.class);
        when(instance.get()).thenReturn(value);
        return instance;
    }
}
