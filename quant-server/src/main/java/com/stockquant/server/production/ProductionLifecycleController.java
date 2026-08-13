package com.stockquant.server.production;

import com.stockquant.server.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Loopback-only graceful stop used by the fixed local host broker. */
@RestController
@RequestMapping("/api/system/lifecycle")
@ConditionalOnProperty(prefix = "stockquant.production", name = "enabled",
        havingValue = "true")
public final class ProductionLifecycleController {
    private final Consumer<Integer> exit;
    private final AtomicBoolean stopping = new AtomicBoolean();

    @Autowired
    public ProductionLifecycleController(
            org.springframework.context.ConfigurableApplicationContext context
    ) {
        this(status -> System.exit(status));
    }

    ProductionLifecycleController(Consumer<Integer> exit) {
        this.exit = exit;
    }

    @PostMapping("/stop")
    public ApiResponse<?> stop(HttpServletRequest request) {
        if (!"127.0.0.1".equals(request.getRemoteAddr())) {
            throw new IllegalStateException("M6_LIFECYCLE_LOOPBACK_REQUIRED");
        }
        boolean initiated = stopping.compareAndSet(false, true);
        if (initiated) {
            Thread closer = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } finally {
                    exit.accept(0);
                }
            }, "stock-quant-production-graceful-stop");
            closer.setDaemon(false);
            closer.start();
        }
        return ApiResponse.ok(Map.of("state", initiated
                ? "STOPPING" : "ALREADY_STOPPING", "realTrading", false));
    }
}
