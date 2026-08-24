package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadLauncher;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadSpec;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesOpenSearchDomainManagerTest {

    @Test
    void buildsPersistentSingleNodeWorkload() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var config = config("opensearchproject/opensearch:2.19.5");
        var manager = new KubernetesOpenSearchDomainManager(launcher, config);
        var domain = domain("domain", "OpenSearch_2.19", "volume-id");

        manager.startDomain(domain);

        var captor = org.mockito.ArgumentCaptor.forClass(KubernetesWorkloadSpec.class);
        verify(launcher).launch(captor.capture(), org.mockito.ArgumentMatchers.eq(false));
        var spec = captor.getValue();
        assertThat(spec.image()).isEqualTo("opensearchproject/opensearch:2.19.5");
        assertThat(spec.ports()).containsExactly(9200);
        assertThat(spec.environment()).contains(
                "discovery.type=single-node",
                "DISABLE_SECURITY_PLUGIN=true",
                "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD=FlociAdmin1!");
        assertThat(spec.storage()).get().satisfies(storage -> {
            assertThat(storage.mountPath()).isEqualTo("/usr/share/opensearch/data");
            assertThat(storage.subPath()).contains("data");
        });
        assertThat(spec.fsGroup()).contains(1000L);
        assertThat(domain.getEndpoint()).isEqualTo("http://" + spec.name() + ":9200");
        assertThat(domain.getContainerId()).isEqualTo(spec.name());
    }

    @Test
    void stopRetainsStorageAndResourceRemovalDeletesIt() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var manager = new KubernetesOpenSearchDomainManager(
                launcher, config("opensearchproject/opensearch:2.19.5"));
        var domain = domain("domain", "OpenSearch_2.11", "volume-id");

        manager.stopDomain(domain);
        manager.removeDomainStorage(domain);

        verify(launcher).delete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(false));
        verify(launcher).delete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void failedCreationDeletesWorkloadAndStorage() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        when(launcher.launch(
                org.mockito.ArgumentMatchers.any(KubernetesWorkloadSpec.class),
                org.mockito.ArgumentMatchers.eq(false)))
                .thenThrow(new IllegalStateException("launch failed"));
        var manager = new KubernetesOpenSearchDomainManager(
                launcher, config("opensearchproject/opensearch:2.19.5"));
        var domain = domain("domain", "OpenSearch_2.11", "volume-id");

        assertThrows(IllegalStateException.class, () -> manager.startDomain(domain));

        verify(launcher).delete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void shutdownLeavesWorkloadRunning() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var manager = new KubernetesOpenSearchDomainManager(
                launcher, config("opensearchproject/opensearch:2.19.5"));
        var domain = domain("domain", "OpenSearch_2.11", "volume-id");

        manager.startDomain(domain);
        manager.stopAll();

        verify(launcher, org.mockito.Mockito.never()).delete(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private static EmulatorConfig config(String image) {
        var config = mock(EmulatorConfig.class);
        var services = mock(EmulatorConfig.ServicesConfig.class);
        var opensearch = mock(EmulatorConfig.OpenSearchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.opensearch()).thenReturn(opensearch);
        when(opensearch.defaultImage()).thenReturn(Optional.of(image));
        return config;
    }

    private static Domain domain(String name, String version, String volumeId) {
        var domain = new Domain();
        domain.setDomainName(name);
        domain.setEngineVersion(version);
        domain.setVolumeId(volumeId);
        return domain;
    }
}
