package com.wzj.trigger.http;

import com.wzj.types.observability.TradeRequestTiming;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Header is captured before serialization, not after a committed servlet response. */
@ControllerAdvice(assignableTypes = MarketTradeController.class)
@ConditionalOnProperty(name="trade.trace.enabled", havingValue="true")
public class TradeTimingAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        TradeRequestTiming.Trace trace = TradeRequestTiming.current();
        if (trace != null) response.getHeaders().set("Server-Timing", trace.header());
        return body;
    }
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    @ConditionalOnProperty(name="trade.trace.enabled", havingValue="true")
    public static class TimingFilter extends OncePerRequestFilter {
        @Override protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/api/v1/group-buy/orders");
        }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            TradeRequestTiming.start();
            try { chain.doFilter(request, response); } finally { TradeRequestTiming.clear(); }
        }
    }
}
