package com.latifi.api.config;

import com.latifi.api.shared.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** Answers 401 with an ApiError when a request reaches a protected route without a valid token. */
@Component
public class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ApiErrorWriter writer;

  ApiErrorAuthenticationEntryPoint(ApiErrorWriter writer) {
    this.writer = writer;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    writer.write(request, response, ApiErrorCode.UNAUTHORIZED);
  }
}
