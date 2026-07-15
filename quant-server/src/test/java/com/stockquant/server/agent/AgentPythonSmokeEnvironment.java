package com.stockquant.server.agent;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.URI;
import java.net.URISyntaxException;

final class AgentPythonSmokeEnvironment {

    private static final String VARIABLE = "STOCK_QUANT_PYTHON_BASE_URL";

    private AgentPythonSmokeEnvironment() {}

    static void registerBaseUrl(DynamicPropertyRegistry registry) {
        String baseUrl = validate(System.getenv(VARIABLE));
        registry.add("stockquant.agent-team.base-url", () -> baseUrl);
    }

    static String validate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("本地Python冒烟服务地址未设置");
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException error) {
            throw new IllegalStateException("本地Python冒烟服务地址格式无效");
        }
        int port = uri.getPort();
        boolean valid = "http".equals(uri.getScheme())
                && "127.0.0.1".equals(uri.getHost())
                && port >= 1024 && port <= 65535
                && uri.getRawUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && value.equals("http://127.0.0.1:" + port);
        if (!valid) {
            throw new IllegalStateException("本地Python冒烟服务地址不符合回环安全要求");
        }
        return value;
    }
}
