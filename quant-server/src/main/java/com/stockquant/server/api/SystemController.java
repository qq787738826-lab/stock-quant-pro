package com.stockquant.server.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;

@RestController @RequestMapping("/api")
public class SystemController {
    private final JdbcClient jdbc;
    public SystemController(JdbcClient jdbc) { this.jdbc=jdbc; }
    @GetMapping("/health") public ApiResponse<Map<String,Object>> health() {
        Integer db = jdbc.sql("select 1").query(Integer.class).single();
        return ApiResponse.ok(Map.of("status","UP","database",db==1?"UP":"DOWN","time", LocalDateTime.now(),"version","1.3.1"));
    }
    @GetMapping("/market/overview") public ApiResponse<Map<String,Object>> overview() {
        return ApiResponse.ok(Map.of("marketState","CLOSED_OR_UNKNOWN","breadth",0,"hotSector","待数据更新","riskLevel","MEDIUM"));
    }
}
