package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.services.opensearch.model.Domain;

public interface OpenSearchRuntime {

    void startDomain(Domain domain);

    boolean isReady(Domain domain);

    void stopDomain(Domain domain);

    void removeDomainStorage(Domain domain);

    void stopAll();
}
