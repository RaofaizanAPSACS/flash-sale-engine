package com.example.flash_sale_engine.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Custom Micrometer metrics for monitoring flash sale performance.
 *
 * Metrics exposed at /actuator/metrics and /actuator/prometheus:
 * - flash_sale.requests.total: Total purchase requests received
 * - flash_sale.requests.rate_limited: Requests rejected by rate limiter
 * - flash_sale.requests.sold_out: Requests rejected because stock = 0
 * - flash_sale.purchases.success: Successful stock decrements (orders enqueued)
 * - flash_sale.purchase.latency: End-to-end purchase processing time
 * - flash_sale.orders.processed: Orders written to database by worker
 */
@Configuration
public class MetricsConfig {

    @Bean
    public Counter requestsTotal(MeterRegistry registry) {
        return Counter.builder("flash_sale.requests.total")
                .description("Total purchase requests")
                .register(registry);
    }

    @Bean
    public Counter requestsRateLimited(MeterRegistry registry) {
        return Counter.builder("flash_sale.requests.rate_limited")
                .description("Requests rejected by rate limiter")
                .register(registry);
    }

    @Bean
    public Counter requestsSoldOut(MeterRegistry registry) {
        return Counter.builder("flash_sale.requests.sold_out")
                .description("Requests rejected - sold out")
                .register(registry);
    }

    @Bean
    public Counter purchasesSuccess(MeterRegistry registry) {
        return Counter.builder("flash_sale.purchases.success")
                .description("Successful purchases (orders enqueued)")
                .register(registry);
    }

    @Bean
    public Counter ordersProcessed(MeterRegistry registry) {
        return Counter.builder("flash_sale.orders.processed")
                .description("Orders written to database")
                .register(registry);
    }

    @Bean
    public Timer purchaseLatency(MeterRegistry registry) {
        return Timer.builder("flash_sale.purchase.latency")
                .description("Purchase endpoint latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
