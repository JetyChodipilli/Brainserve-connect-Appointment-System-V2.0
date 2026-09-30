package com.brainserve.appointment.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@Configuration(proxyBeanMethods = false)
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class PaginationConfiguration {

    @Bean
    PageableHandlerMethodArgumentResolverCustomizer boundedPageRequests() {
        return resolver -> {
            resolver.setFallbackPageable(PageRequest.of(0, 50));
            resolver.setMaxPageSize(100);
            resolver.setOneIndexedParameters(false);
        };
    }
}