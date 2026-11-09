package com.latifi.api.config;

import com.latifi.api.identity.infrastructure.AuthRateLimiter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One explicit chain: stateless, no cookies, no forms, no basic authentication. Everything is
 * protected unless it is listed here. Failures raised inside the chain answer with the same
 * ApiError as the controllers. The bearer filter runs on every request that carries an
 * Authorization header, so a bad token on a public route still answers 401.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ApiErrorAuthenticationEntryPoint entryPoint,
      ApiErrorAccessDeniedHandler accessDeniedHandler,
      JwtDecoder jwtDecoder,
      AuthRateLimiter limiter,
      ClientIpResolver clientIps,
      ApiErrorWriter errorWriter)
      throws Exception {
    // Created here and not exposed as a bean, so the servlet container does not register it twice.
    AuthRateLimitFilter rateLimitFilter = new AuthRateLimitFilter(limiter, clientIps, errorWriter);
    http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> j.decoder(jwtDecoder).jwtAuthenticationConverter(jwtConverter()))
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
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

  /** The token grants no authorities. The principal name is the {@code sub} claim. */
  private static JwtAuthenticationConverter jwtConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
    converter.setPrincipalClaimName("sub");
    return converter;
  }
}
