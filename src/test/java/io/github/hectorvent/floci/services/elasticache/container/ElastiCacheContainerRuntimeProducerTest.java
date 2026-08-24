package io.github.hectorvent.floci.services.elasticache.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheContainerRuntimeProducerTest {

    @Test
    void selectsKubernetesRuntimeWithoutResolvingDocker() {
        var config = config("kubernetes");
        var docker = mock(Instance.class);
        var kubernetes = mock(Instance.class);
        var selected = mock(ElastiCacheContainerRuntime.class);
        when(kubernetes.get()).thenReturn(selected);

        assertThat(new ElastiCacheContainerRuntimeProducer()
                .runtime(config, docker, kubernetes)).isSameAs(selected);
        verify(docker, org.mockito.Mockito.never()).get();
    }

    @Test
    void selectsDockerRuntime() {
        var config = config("docker");
        var docker = mock(Instance.class);
        var kubernetes = mock(Instance.class);
        var selected = mock(ElastiCacheContainerRuntime.class);
        when(docker.get()).thenReturn(selected);

        assertThat(new ElastiCacheContainerRuntimeProducer()
                .runtime(config, docker, kubernetes)).isSameAs(selected);
        verify(kubernetes, org.mockito.Mockito.never()).get();
    }

    @Test
    void selectsKubernetesMemcachedRuntime() {
        var config = config("kubernetes");
        var docker = mock(Instance.class);
        var kubernetes = mock(Instance.class);
        var selected = mock(ElastiCacheMemcachedRuntime.class);
        when(kubernetes.get()).thenReturn(selected);

        assertThat(new ElastiCacheContainerRuntimeProducer()
                .memcachedRuntime(config, docker, kubernetes)).isSameAs(selected);
        verify(docker, org.mockito.Mockito.never()).get();
    }

    @Test
    void rejectsInvalidExecutor() {
        var config = config("containerd");

        assertThatThrownBy(() -> ElastiCacheContainerRuntimeProducer.requireValidExecutor(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown floci.services.elasticache.executor 'containerd'. "
                        + "Valid values: docker, kubernetes");
    }

    private static EmulatorConfig config(String executor) {
        var config = mock(EmulatorConfig.class);
        var services = mock(EmulatorConfig.ServicesConfig.class);
        var elasticache = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.elasticache()).thenReturn(elasticache);
        when(elasticache.executor()).thenReturn(executor);
        return config;
    }
}
