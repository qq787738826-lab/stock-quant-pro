package com.stockquant.server.agent.api;

import com.stockquant.server.agent.exception.AgentTaskNotFoundException;
import com.stockquant.server.agent.exception.AgentTeamException;
import com.stockquant.server.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice(assignableTypes = AgentTaskController.class)
public class AgentTaskExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskExceptionHandler.class);

    @ExceptionHandler(AgentTaskNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> notFound(AgentTaskNotFoundException error) {
        return ResponseEntity.status(404).body(ApiResponse.fail(error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(error.getMessage()));
    }

    @ExceptionHandler(AgentTeamException.class)
    ResponseEntity<ApiResponse<Void>> agentTeamFailure(AgentTeamException error) {
        log.error("智能体任务处理失败", error);
        return ResponseEntity.internalServerError().body(ApiResponse.fail("智能体任务处理失败"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiResponse.fail(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("请求JSON字段或枚举值无效"));
    }
}
