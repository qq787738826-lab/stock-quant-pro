package com.stockquant.server.api;

import com.stockquant.server.service.SignalService;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class SignalController {
    private final SignalService service;
    public SignalController(SignalService service) { this.service=service; }
    @GetMapping("/signals/daily") public ApiResponse<?> signals(@RequestParam(defaultValue="10") int limit) { return ApiResponse.ok(service.dailySignals(limit)); }
    @GetMapping("/trade-plans") public ApiResponse<?> plans(@RequestParam(defaultValue="10") int limit) { return ApiResponse.ok(service.plans(limit)); }
}
