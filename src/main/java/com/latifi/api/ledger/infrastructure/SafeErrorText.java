package com.latifi.api.ledger.infrastructure;

import java.util.regex.Pattern;

/**
 * Turns an exception into one line for the log: the class and the message of the exception and of
 * each cause. A transport message can quote the node URL, and that URL can carry an API key, so
 * every URL and every {@code key=value} secret is replaced before the text leaves this class. The
 * line has no stack trace and a fixed maximum length.
 */
public final class SafeErrorText {

  private static final int MAX_CAUSES = 5;
  private static final int MAX_MESSAGE = 300;
  private static final String REDACTED = "[redacted]";

  private static final Pattern URL = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://\\S+");
  private static final Pattern SECRET =
      Pattern.compile("(?i)\\b(api[-_]?key|apikey|key|token|secret|password|pwd|auth)=\\S+");

  private SafeErrorText() {}

  /**
   * The sanitized description of the exception and its causes, for example {@code A: x <- B: y}.
   */
  public static String of(Throwable error) {
    StringBuilder text = new StringBuilder();
    Throwable current = error;
    for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
      if (depth > 0) {
        text.append(" <- ");
      }
      text.append(current.getClass().getSimpleName());
      String message = current.getMessage();
      if (message != null && !message.isBlank()) {
        text.append(": ").append(clean(message));
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return text.toString();
  }

  private static String clean(String message) {
    String oneLine = message.replaceAll("\\s+", " ").strip();
    String withoutUrls = URL.matcher(oneLine).replaceAll(REDACTED);
    String withoutSecrets = SECRET.matcher(withoutUrls).replaceAll("$1=" + REDACTED);
    return withoutSecrets.length() <= MAX_MESSAGE
        ? withoutSecrets
        : withoutSecrets.substring(0, MAX_MESSAGE) + "...";
  }
}
