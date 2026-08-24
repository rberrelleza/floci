package io.github.hectorvent.floci.services.elasticache.container;

import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadLauncher;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesElastiCacheContainerManagerTest {

    @Test
    void buildsPersistentValkeyWorkload() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var manager = new KubernetesElastiCacheContainerManager(
                launcher, mock(ElastiCacheBackendProbe.class));

        var handle = manager.start("group", "valkey/valkey:8");

        var captor = org.mockito.ArgumentCaptor.forClass(KubernetesWorkloadSpec.class);
        verify(launcher).launch(captor.capture());
        var spec = captor.getValue();
        assertThat(spec.image()).isEqualTo("valkey/valkey:8");
        assertThat(spec.ports()).containsExactly(6379);
        assertThat(spec.args()).containsExactlyElementsOf(List.of("--appendonly", "yes"));
        assertThat(spec.storage()).get().satisfies(storage -> {
            assertThat(storage.mountPath()).isEqualTo("/data");
            assertThat(storage.subPath()).contains("data");
        });
        assertThat(spec.fsGroup()).contains(999L);
        assertThat(handle.getHost()).isEqualTo(spec.name());
    }

    @Test
    void shutdownLeavesValkeyWorkloadRunning() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var manager = new KubernetesElastiCacheContainerManager(
                launcher, mock(ElastiCacheBackendProbe.class));

        manager.start("group", "valkey/valkey:8");
        manager.stopAll();

        verify(launcher, org.mockito.Mockito.never()).delete(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void failedCreationDeletesWorkloadAndStorage() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        when(launcher.launch(any())).thenThrow(new IllegalStateException("launch failed"));
        var manager = new KubernetesElastiCacheContainerManager(
                launcher, mock(ElastiCacheBackendProbe.class));

        assertThrows(IllegalStateException.class, () -> manager.start("group", "valkey/valkey:8"));

        verify(launcher).delete("floci-valkey-group", true);
    }

    @Test
    void resourceRemovalDeletesValkeyStorage() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var manager = new KubernetesElastiCacheContainerManager(
                launcher, mock(ElastiCacheBackendProbe.class));

        manager.removeStorage("group");

        verify(launcher).delete("floci-valkey-group", true);
    }
}
