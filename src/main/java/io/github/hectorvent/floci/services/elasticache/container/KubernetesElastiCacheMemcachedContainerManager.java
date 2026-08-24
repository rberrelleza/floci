package io.github.hectorvent.floci.services.elasticache.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadLauncher;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs non-persistent Memcached workloads in Kubernetes. Floci shutdown leaves them running
 * in Kubernetes for consistency with the other Kubernetes executors.
 */
@ApplicationScoped
@Typed(KubernetesElastiCacheMemcachedContainerManager.class)
public class KubernetesElastiCacheMemcachedContainerManager implements ElastiCacheMemcachedRuntime {

    private static final Logger LOG = Logger.getLogger(KubernetesElastiCacheMemcachedContainerManager.class);
    private static final int BACKEND_PORT = 11211;

    private final KubernetesWorkloadLauncher workloadLauncher;
    private final ElastiCacheBackendProbe probe;
    private final EmulatorConfig config;
    private final Map<String, ElastiCacheContainerHandle> activeContainers = new ConcurrentHashMap<>();

    @Inject
    public KubernetesElastiCacheMemcachedContainerManager(KubernetesWorkloadLauncher workloadLauncher,
                                                          ElastiCacheBackendProbe probe,
                                                          EmulatorConfig config) {
        this.workloadLauncher = workloadLauncher;
        this.probe = probe;
        this.config = config;
    }

    public KubernetesElastiCacheMemcachedContainerManager(KubernetesWorkloadLauncher workloadLauncher,
                                                          ElastiCacheBackendProbe probe) {
        this(workloadLauncher, probe, null);
    }

    @Override
    public ElastiCacheContainerHandle start(String clusterId, String image) {
        var workloadName = workloadName(clusterId);
        LOG.infov("Starting Memcached Kubernetes workload for cluster {0}", clusterId);
        var spec = KubernetesWorkloadSpec.builder(workloadName, "elasticache", clusterId, image)
                .withPort(BACKEND_PORT)
                .build();
        try {
            workloadLauncher.launch(spec);
            var handle = new ElastiCacheContainerHandle(workloadName, clusterId, workloadName, BACKEND_PORT);
            activeContainers.put(clusterId, handle);
            probe.waitForMemcached(clusterId, handle.getHost(), handle.getPort());
            return handle;
        } catch (RuntimeException | Error failure) {
            try {
                workloadLauncher.delete(workloadName, true);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public void stop(ElastiCacheContainerHandle handle) {
        if (handle == null) {
            return;
        }
        activeContainers.remove(handle.getGroupId());
        workloadLauncher.delete(handle.getContainerId(), false);
    }

    @Override
    public void removeStorage(String clusterId) {
        workloadLauncher.delete(workloadName(clusterId), true);
    }

    @Override
    public void stopAll() {
        var count = activeContainers.size();
        activeContainers.clear();
        if (count > 0) {
            LOG.infov("Leaving {0} Memcached Kubernetes workload(s) running across Floci shutdown", count);
        }
    }

    private String workloadName(String clusterId) {
        var storageName = config == null
                ? "floci-memcached-" + clusterId
                : ContainerStorageHelper.resourceName(config, "memcached", null, clusterId);
        return KubernetesWorkloadLauncher.sanitizeName(storageName);
    }
}
