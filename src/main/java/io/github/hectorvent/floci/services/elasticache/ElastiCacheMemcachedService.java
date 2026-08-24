package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedRuntime;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ElastiCacheMemcachedService {

    private static final Logger LOG = Logger.getLogger(ElastiCacheMemcachedService.class);
    private static final String ENGINE = "memcached";
    private static final String ENGINE_VERSION = "1.6.22";

    private final StorageBackend<String, CacheCluster> clusters;
    private final ElastiCacheMemcachedRuntime containerManager;
    private final EmulatorConfig config;

    @Inject
    public ElastiCacheMemcachedService(ElastiCacheMemcachedRuntime containerManager,
                                       StorageFactory storageFactory,
                                       EmulatorConfig config) {
        this.containerManager = containerManager;
        this.config = config;
        this.clusters = storageFactory.create("elasticache", "elasticache-cache-clusters.json",
                new TypeReference<Map<String, CacheCluster>>() {});
    }

    public void restorePersistedRuntime() {
        if (!"kubernetes".equalsIgnoreCase(config.services().elasticache().executor())) {
            return;
        }
        for (CacheCluster cluster : clusters.scan(key -> true)) {
            try {
                var handle = containerManager.start(
                        cluster.getCacheClusterId(),
                        config.services().elasticache().defaultMemcachedImage());
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());
                clusters.put(cluster.getCacheClusterId(), cluster);
            } catch (RuntimeException exception) {
                LOG.warnv(exception, "Failed to restore Memcached cluster {0}",
                        cluster.getCacheClusterId());
            }
        }
    }

    public CacheCluster createCacheCluster(String clusterId) {
        if (clusters.get(clusterId).isPresent()) {
            throw new AwsException("CacheClusterAlreadyExistsFault",
                    "Cache cluster " + clusterId + " already exists.", 400);
        }

        String image = config.services().elasticache().defaultMemcachedImage();
        LOG.infov("Creating Memcached cluster {0} with image {1}", clusterId, image);

        ElastiCacheContainerHandle handle = containerManager.start(clusterId, image);

        String endpointHost = resolveEndpointHost(handle);
        Endpoint endpoint = new Endpoint(endpointHost, handle.getPort());

        CacheCluster cluster = new CacheCluster(
                clusterId, CacheClusterStatus.AVAILABLE, ENGINE, ENGINE_VERSION,
                endpoint, Instant.now());
        cluster.setContainerId(handle.getContainerId());
        cluster.setContainerHost(handle.getHost());
        cluster.setContainerPort(handle.getPort());

        clusters.put(clusterId, cluster);
        LOG.infov("Memcached cluster {0} created, endpoint={1}:{2}", clusterId, endpointHost, handle.getPort());
        return cluster;
    }

    public CacheCluster getCacheCluster(String clusterId) {
        return clusters.get(clusterId).orElseThrow(() ->
                new AwsException("CacheClusterNotFound",
                        "Cache cluster " + clusterId + " not found.", 404));
    }

    public Collection<CacheCluster> listCacheClusters(String filterClusterId) {
        if (filterClusterId != null && !filterClusterId.isBlank()) {
            return clusters.get(filterClusterId)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("CacheClusterNotFound",
                            "Cache cluster " + filterClusterId + " not found.", 404));
        }
        return clusters.scan(k -> true);
    }

    public CacheCluster deleteCacheCluster(String clusterId) {
        CacheCluster cluster = getCacheCluster(clusterId);

        cluster.setCacheClusterStatus(CacheClusterStatus.DELETING);
        clusters.put(clusterId, cluster);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new ElastiCacheContainerHandle(
                    cluster.getContainerId(), clusterId,
                    cluster.getContainerHost(), cluster.getContainerPort()));
        }
        containerManager.removeStorage(clusterId);

        clusters.delete(clusterId);
        LOG.infov("Memcached cluster {0} deleted", clusterId);
        return cluster;
    }

    private String resolveEndpointHost(ElastiCacheContainerHandle handle) {
        return config.hostname().orElse(handle.getHost());
    }
}
