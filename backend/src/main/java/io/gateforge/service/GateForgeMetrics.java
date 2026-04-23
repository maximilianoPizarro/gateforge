package io.gateforge.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class GateForgeMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger productsDiscovered = new AtomicInteger(0);

    @Inject
    public GateForgeMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("gateforge_products_discovered", productsDiscovered);
    }

    public void recordMigration(String product, String status) {
        Counter.builder("gateforge_migrations_total")
                .tag("product", product)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public Timer.Sample startMigrationTimer() {
        return Timer.start(registry);
    }

    public void stopMigrationTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("gateforge_migration_duration_seconds")
                .register(registry));
    }

    public void recordChatRequest(String type) {
        Counter.builder("gateforge_ai_chat_requests")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    public void setProductsDiscovered(int count) {
        productsDiscovered.set(count);
    }
}
