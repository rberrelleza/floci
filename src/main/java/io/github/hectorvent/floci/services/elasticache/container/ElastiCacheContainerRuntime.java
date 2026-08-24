package io.github.hectorvent.floci.services.elasticache.container;

public interface ElastiCacheContainerRuntime {

    ElastiCacheContainerHandle start(String groupId, String image);

    void stop(ElastiCacheContainerHandle handle);

    void stopByGroupId(String groupId);

    void removeStorage(String groupId);

    void stopAll();
}
