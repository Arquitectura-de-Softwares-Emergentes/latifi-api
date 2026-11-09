package com.latifi.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Adds the {@code /api/v1} prefix to every application controller. Controllers declare their paths
 * without it, which matches how the OpenAPI contract writes them.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

  static final String API_PREFIX = "/api/v1";

  @Override
  public void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.addPathPrefix(
        API_PREFIX,
        HandlerTypePredicate.forBasePackage("com.latifi.api")
            .and(HandlerTypePredicate.forAnnotation(RestController.class)));
  }
}
