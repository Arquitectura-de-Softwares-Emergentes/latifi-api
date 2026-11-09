package com.latifi.api.config;

import com.latifi.api.shared.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Answers 403 with an ApiError when an authenticated caller touches data of another address. */
@Component
public class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

  private final ApiErrorWriter writer;

  ApiErrorAccessDeniedHandler(ApiErrorWriter writer) {
    this.writer = writer;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    writer.write(request, response, ApiErrorCode.FORBIDDEN_NOT_OWNER);
  }
}
