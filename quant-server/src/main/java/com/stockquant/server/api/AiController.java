package com.stockquant.server.api;

import com.stockquant.server.service.AiService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/ai")
public class AiController {
    private final AiService service;
    public AiController(AiService service) { this.service=service; }
    @PostMapping("/analyze") public ApiResponse<?> analyze(@RequestBody Map<String,String> body) { return ApiResponse.ok(service.analyze(body.getOrDefault("symbol","600000"))); }
}
