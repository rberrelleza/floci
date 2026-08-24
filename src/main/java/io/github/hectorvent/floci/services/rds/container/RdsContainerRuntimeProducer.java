package io.github.hectorvent.floci.services.rds.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.jboss.logging.Logger;

import java.util.Locale;

/**
 * Selects the RDS runtime without resolving the unselected Docker or Kubernetes backend.
 */
@ApplicationScoped
public class RdsContainerRuntimeProducer {

    private static final Logger LOG = Logger.getLogger(RdsContainerRuntimeProducer.class);

    void validateExecutor(@Observes StartupEvent event, EmulatorConfig config) {
        requireValidExecutor(config);
    }

    @Produces
    @ApplicationScoped
    RdsContainerRuntime runtime(EmulatorConfig config,
                                Instance<RdsContainerManager> docker,
                                Instance<KubernetesRdsContainerManager> kubernetes) {
        var executor = requireValidExecutor(config);
        if (executor.equals("kubernetes")) {
            LOG.info("RDS executor: kubernetes (databases run as StatefulSets)");
            return kubernetes.get();
        }
        LOG.info("RDS executor: docker (databases run as containers)");
        return docker.get();
    }

    static String requireValidExecutor(EmulatorConfig config) {
        var executor = config.services().rds().executor().trim().toLowerCase(Locale.ROOT);
        if (!executor.equals("docker") && !executor.equals("kubernetes")) {
            throw new IllegalArgumentException(
                    "Unknown floci.services.rds.executor '" + executor
                            + "'. Valid values: docker, kubernetes");
        }
        return executor;
    }
}
