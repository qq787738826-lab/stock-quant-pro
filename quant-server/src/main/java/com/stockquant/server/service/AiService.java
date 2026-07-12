package com.stockquant.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final RestTemplate restTemplate;
    private final String aiServiceUrl;

    public AiService(@Value("${quant.ai-service-url}") String aiServiceUrl) {
        this.aiServiceUrl = aiServiceUrl.endsWith("/")
                ? aiServiceUrl.substring(0, aiServiceUrl.length() - 1)
                : aiServiceUrl;

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(25_000);

        this.restTemplate = new RestTemplate(requestFactory);
        log.info("AI service URL: {}", this.aiServiceUrl);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(String symbol) {
        String url = aiServiceUrl + "/ai/analyze";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            Map<String, String> requestBody = new LinkedHashMap<>();
            requestBody.put("symbol", symbol);

            HttpEntity<Map<String, String>> requestEntity =
                    new HttpEntity<>(requestBody, headers);

            log.info("Calling AI service: url={}, symbol={}", url, symbol);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );

            Map<String, Object> result = response.getBody();
            if (result == null) {
                throw new IllegalStateException("AI服务返回空数据");
            }

            log.info(
                    "AI analysis success: symbol={}, mode={}, source={}",
                    symbol,
                    result.get("mode"),
                    result.get("dataSource")
            );
            return result;

        } catch (HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            log.error(
                    "AI service returned error: status={}, url={}, symbol={}, body={}",
                    e.getStatusCode(),
                    url,
                    symbol,
                    responseBody
            );
            return fallback(
                    symbol,
                    "行情源暂时不可用，已降级。Python返回：" + responseBody
            );
        } catch (Exception e) {
            log.error(
                    "AI service call failed: url={}, symbol={}",
                    url,
                    symbol,
                    e
            );
            return fallback(
                    symbol,
                    "Java调用AI服务超时或连接失败，已降级。"
            );
        }
    }

    private Map<String, Object> fallback(String symbol, String reason) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("symbol", symbol);
        fallback.put("mode", "JAVA_FALLBACK");
        fallback.put("summary", reason);
        fallback.put("score", 0);
        fallback.put("bullish", List.of());
        fallback.put("bearish", List.of("行情数据暂时不可用，请稍后重试"));
        fallback.put("riskLevel", "UNKNOWN");
        fallback.put("disclaimer", "仅供投研，不构成投资建议。");
        return fallback;
    }
}
