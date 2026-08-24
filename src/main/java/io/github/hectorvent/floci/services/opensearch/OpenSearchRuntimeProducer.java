package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

import java.util.Locale;

@ApplicationScoped
public class OpenSearchRuntimeProducer {

    @Produces
    @ApplicationScoped
    OpenSearchRuntime runtime(EmulatorConfig config, Instance<OpenSearchDomainManager> docker,
                              Instance<KubernetesOpenSearchDomainManager> kubernetes) {
        if (requireValidExecutor(config).equals("kubernetes")) {
            return kubernetes.get();
        }
        return docker.get();
    }

    static String requireValidExecutor(EmulatorConfig config) {
        var executor = config.services().opensearch().executor()
                .trim().toLowerCase(Locale.ROOT);
        if (!executor.equals("docker") && !executor.equals("kubernetes")) {
            throw new IllegalArgumentException(
                    "Unknown floci.services.opensearch.executor '" + executor
                            + "'. Valid values: docker, kubernetes");
        }
        return executor;
    }
}
