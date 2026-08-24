package io.github.hectorvent.floci.services.elasticache.container;

public interface ElastiCacheMemcachedRuntime {

    ElastiCacheContainerHandle start(String clusterId, String image);

    void stop(ElastiCacheContainerHandle handle);

    void removeStorage(String clusterId);

    void stopAll();
}
