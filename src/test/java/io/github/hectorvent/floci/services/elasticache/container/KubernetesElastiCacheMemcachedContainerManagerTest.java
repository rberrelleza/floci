package io.github.hectorvent.floci.services.elasticache.container;

import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadLauncher;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesElastiCacheMemcachedContainerManagerTest {

    @Test
    void buildsNonPersistentMemcachedWorkload() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var manager = new KubernetesElastiCacheMemcachedContainerManager(
                launcher, mock(ElastiCacheBackendProbe.class));

        manager.start("cluster", "memcached:1.6");

        var captor = org.mockito.ArgumentCaptor.forClass(KubernetesWorkloadSpec.class);
        verify(launcher).launch(captor.capture());
        var spec = captor.getValue();
        assertThat(spec.image()).isEqualTo("memcached:1.6");
        assertThat(spec.ports()).containsExactly(11211);
        assertThat(spec.storage()).isEmpty();
        assertThat(spec.fsGroup()).isEmpty();
    }

    @Test
    void shutdownLeavesMemcachedWorkloadRunning() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var manager = new KubernetesElastiCacheMemcachedContainerManager(
                launcher, mock(ElastiCacheBackendProbe.class));

        manager.start("cluster", "memcached:1.6");
        manager.stopAll();

        verify(launcher, org.mockito.Mockito.never()).delete(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void failedCreationDeletesWorkloadWithStorageFlag() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        when(launcher.launch(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("launch failed"));
        var manager = new KubernetesElastiCacheMemcachedContainerManager(
                launcher, mock(ElastiCacheBackendProbe.class));

        assertThrows(IllegalStateException.class, () -> manager.start("cluster", "memcached:1.6"));

        verify(launcher).delete("floci-memcached-cluster", true);
    }

    @Test
    void resourceRemovalIsIdempotentWithoutPersistentStorage() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var manager = new KubernetesElastiCacheMemcachedContainerManager(
                launcher, mock(ElastiCacheBackendProbe.class));

        manager.removeStorage("cluster");

        verify(launcher).delete("floci-memcached-cluster", true);
    }
}
