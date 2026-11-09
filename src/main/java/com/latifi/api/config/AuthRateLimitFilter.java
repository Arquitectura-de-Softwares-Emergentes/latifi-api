package com.latifi.api.config;

import com.latifi.api.identity.infrastructure.AuthRateLimiter;
import com.latifi.api.identity.infrastructure.AuthRateLimiter.Decision;
import com.latifi.api.shared.ApiErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits every request under {@code /api/v1/auth/} per client. SecurityConfig creates it and adds
 * it to the chain, and it is not a bean, so the container does not register it a second time.
 */
class AuthRateLimitFilter extends OncePerRequestFilter {

  private static final String AUTH_PREFIX = WebConfig.API_PREFIX + "/auth/";

  private final AuthRateLimiter limiter;
  private final ClientIpResolver clientIps;
  private final ApiErrorWriter writer;

  AuthRateLimitFilter(AuthRateLimiter limiter, ClientIpResolver clientIps, ApiErrorWriter writer) {
    this.limiter = limiter;
    this.clientIps = clientIps;
    this.writer = writer;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(AUTH_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Decision decision = limiter.tryClient(clientIps.resolve(request));
    if (decision.allowed()) {
      chain.doFilter(request, response);
      return;
    }
    writer.write(request, response, ApiErrorCode.TOO_MANY_REQUESTS, decision.retryAfterSeconds());
  }
}
