package com.latifi.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One explicit chain: stateless, no cookies, no forms, no basic authentication. Everything is
 * protected unless it is listed here. Failures raised inside the chain answer with the same
 * ApiError as the controllers. The bearer token filter joins this chain with the token issuing
 * plan.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ApiErrorAuthenticationEntryPoint entryPoint,
      ApiErrorAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        HttpMethod.POST, "/api/v1/auth/challenge", "/api/v1/auth/verify")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/profiles/*")
                    .permitAll()
                    .requestMatchers(
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated());
    return http.build();
  }
}
