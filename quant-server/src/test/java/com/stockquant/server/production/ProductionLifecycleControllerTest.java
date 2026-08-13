package com.stockquant.server.production;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionLifecycleControllerTest {
    @Test
    void onlyLoopbackCanInitiateOneGracefulJvmExit() throws Exception {
        CountDownLatch exit = new CountDownLatch(1);
        var controller = new ProductionLifecycleController(status -> {
            if (status == 0) exit.countDown();
        });
        HttpServletRequest remote = mock(HttpServletRequest.class);
        when(remote.getRemoteAddr()).thenReturn("192.0.2.1");
        assertThrows(IllegalStateException.class,
                () -> controller.stop(remote));

        HttpServletRequest loopback = mock(HttpServletRequest.class);
        when(loopback.getRemoteAddr()).thenReturn("127.0.0.1");
        assertTrue(controller.stop(loopback).success());
        assertTrue(exit.await(Duration.ofSeconds(2).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS));
    }
}
