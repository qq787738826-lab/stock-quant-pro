package com.stockquant.server.agent;

import org.springframework.test.context.DynamicPropertyRegistry;

final class AgentPostgresTestEnvironment {

    static final String REQUIRED_URL = "jdbc:postgresql://127.0.0.1:5432/stock_quant_test";
    static final String REQUIRED_USERNAME = "stock_quant_test";

    private static final String URL_VARIABLE = "STOCK_QUANT_TEST_DB_URL";
    private static final String USERNAME_VARIABLE = "STOCK_QUANT_TEST_DB_USERNAME";
    private static final String PASSWORD_VARIABLE = "STOCK_QUANT_TEST_DB_PASSWORD";

    private AgentPostgresTestEnvironment() {}

    static void registerDataSource(DynamicPropertyRegistry registry) {
        Credentials credentials = validate(
                System.getenv(URL_VARIABLE),
                System.getenv(USERNAME_VARIABLE),
                System.getenv(PASSWORD_VARIABLE)
        );
        registry.add("spring.datasource.url", credentials::url);
        registry.add("spring.datasource.username", credentials::username);
        registry.add("spring.datasource.password", credentials::password);
    }

    static Credentials validate(String url, String username, String password) {
        if (!REQUIRED_URL.equals(url)) {
            throw new IllegalStateException("专用PostgreSQL测试库URL不符合安全要求");
        }
        if (!REQUIRED_USERNAME.equals(username)) {
            throw new IllegalStateException("专用PostgreSQL测试库用户名不符合安全要求");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("专用PostgreSQL测试库密码未设置");
        }
        return new Credentials(url, username, password);
    }

    record Credentials(String url, String username, String password) {}
}
