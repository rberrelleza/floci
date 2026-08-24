package io.github.hectorvent.floci.services.elasticache.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadLauncher;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs persistent Valkey workloads in Kubernetes. Unlike the Docker backend, which intentionally
 * has no volume today, the Kubernetes backend enables append-only persistence so data survives
 * pod restarts. Workloads survive Floci shutdown so a later service startup can adopt the
 * existing StatefulSet and PVC.
 */
@ApplicationScoped
@Typed(KubernetesElastiCacheContainerManager.class)
public class KubernetesElastiCacheContainerManager implements ElastiCacheContainerRuntime {

    private static final Logger LOG = Logger.getLogger(KubernetesElastiCacheContainerManager.class);
    private static final int BACKEND_PORT = 6379;

    private final KubernetesWorkloadLauncher workloadLauncher;
    private final ElastiCacheBackendProbe probe;
    private final EmulatorConfig config;
    private final Map<String, ElastiCacheContainerHandle> activeContainers = new ConcurrentHashMap<>();

    @Inject
    public KubernetesElastiCacheContainerManager(KubernetesWorkloadLauncher workloadLauncher,
                                                 ElastiCacheBackendProbe probe,
                                                 EmulatorConfig config) {
        this.workloadLauncher = workloadLauncher;
        this.probe = probe;
        this.config = config;
    }

    public KubernetesElastiCacheContainerManager(KubernetesWorkloadLauncher workloadLauncher,
                                                 ElastiCacheBackendProbe probe) {
        this(workloadLauncher, probe, null);
    }

    @Override
    public ElastiCacheContainerHandle start(String groupId, String image) {
        var workloadName = workloadName(groupId);
        LOG.infov("Starting ElastiCache Kubernetes workload for group {0}", groupId);
        var spec = KubernetesWorkloadSpec.builder(workloadName, "elasticache", groupId, image)
                .withPort(BACKEND_PORT)
                .withArgs(List.of("--appendonly", "yes"))
                .withStorage("", "/data", "data")
                .withFsGroup(999)
                .build();
        try {
            workloadLauncher.launch(spec);
            var handle = new ElastiCacheContainerHandle(workloadName, groupId, workloadName, BACKEND_PORT);
            activeContainers.put(groupId, handle);
            probe.waitForValkey(groupId, handle.getHost(), handle.getPort());
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
    public void stopByGroupId(String groupId) {
        var handle = activeContainers.get(groupId);
        if (handle != null) {
            stop(handle);
        } else {
            workloadLauncher.delete(workloadName(groupId), false);
        }
    }

    @Override
    public void removeStorage(String groupId) {
        workloadLauncher.delete(workloadName(groupId), true);
    }

    @Override
    public void stopAll() {
        var count = activeContainers.size();
        activeContainers.clear();
        if (count > 0) {
            LOG.infov("Leaving {0} ElastiCache Kubernetes workload(s) running across Floci shutdown", count);
        }
    }

    private String workloadName(String groupId) {
        var storageName = config == null
                ? "floci-valkey-" + groupId
                : ContainerStorageHelper.resourceName(config, "valkey", null, groupId);
        return KubernetesWorkloadLauncher.sanitizeName(storageName);
    }
}
