package com.inventory.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Isolated development controls, not a production identity or authorization system. */
@Configuration
@Profile("oracle")
@ConditionalOnProperty(name = "inventory.api.enabled", havingValue = "true")
public class OracleApiConfiguration implements WebMvcConfigurer {
    private final OracleTokenGuard guard;

    public OracleApiConfiguration(@Value("${inventory.api.viewer-token:}") String viewer,
                                  @Value("${inventory.api.operator-token:}") String operator) {
        this.guard = new OracleTokenGuard(viewer, operator);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/oracle/**");
    }
}
