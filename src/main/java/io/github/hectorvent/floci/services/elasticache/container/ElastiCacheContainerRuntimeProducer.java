package io.github.hectorvent.floci.services.elasticache.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;

import java.util.Locale;

@ApplicationScoped
public class ElastiCacheContainerRuntimeProducer {

    @Inject
    public ElastiCacheContainerRuntimeProducer() {
    }

    @Produces
    @ApplicationScoped
    ElastiCacheContainerRuntime runtime(
            EmulatorConfig config, Instance<ElastiCacheContainerManager> docker,
            Instance<KubernetesElastiCacheContainerManager> kubernetes) {
        if (requireValidExecutor(config).equals("kubernetes")) {
            return kubernetes.get();
        }
        return docker.get();
    }

    @Produces
    @ApplicationScoped
    ElastiCacheMemcachedRuntime memcachedRuntime(
            EmulatorConfig config, Instance<ElastiCacheMemcachedContainerManager> docker,
            Instance<KubernetesElastiCacheMemcachedContainerManager> kubernetes) {
        if (requireValidExecutor(config).equals("kubernetes")) {
            return kubernetes.get();
        }
        return docker.get();
    }

    static String requireValidExecutor(EmulatorConfig config) {
        var executor = config.services().elasticache().executor()
                .trim().toLowerCase(Locale.ROOT);
        if (!executor.equals("docker") && !executor.equals("kubernetes")) {
            throw new IllegalArgumentException(
                    "Unknown floci.services.elasticache.executor '" + executor
                            + "'. Valid values: docker, kubernetes");
        }
        return executor;
    }
}
