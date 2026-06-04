package com.scaling.exercise.config;

import com.scaling.exercise.service.ProductService;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Web interceptor that sets "X-Cache-Status" header on every response.
 *
 * After the controller method runs:
 *   - If ProductService.getCacheStatus() returns "HIT" → cache hit
 *   - If it returns "MISS" → method body executed (cache miss)
 *   - If it returns "NONE" → not a cached endpoint (e.g., health, create)
 *
 * This header is visible in curl responses and k6 tests, making it
 * easy to verify caching is actually working.
 *
 * The tracking works because:
 * 1. ProductService read methods set cacheStatus to "MISS" in their body
 * 2. The method body only runs on cache miss (@Cacheable skips it on hit)
 * 3. On cache hit, the thread-local stays at whatever the previous value was
 *
 * We pre-set the thread-local to "HIT" before the controller call.
 * If the service method body runs (miss), it overwrites to "MISS".
 * If it doesn't run (hit), it stays "HIT".
 */
@Configuration
public class CacheHitInterceptor implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request,
                                     HttpServletResponse response,
                                     Object handler) {
                // Pre-set to HIT. If the @Cacheable method body runs,
                // it will overwrite this to MISS.
                ProductService.markCacheHit("pending");
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Object handler, Exception ex) {
                String status = ProductService.getCacheStatus();
                if (!"NONE".equals(status)) {
                    response.setHeader("X-Cache-Status", status);
                }
            }
        }).addPathPatterns("/api/products/**");
    }
}
