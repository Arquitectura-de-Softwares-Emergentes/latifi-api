package com.latifi.api.identity.domain;

/**
 * Format rules for the contact and the display name. They are stricter than the OpenAPI schema on
 * purpose, because both values are shown to other people. Every check expects trimmed input.
 */
public final class ContactRules {

  public static final int NAME_MAX = 80;
  public static final int CONTACT_MIN = 3;
  public static final int EMAIL_MAX = 254;
  public static final int PHONE_MAX = 30;
  public static final int PHONE_MIN_DIGITS = 3;

  private ContactRules() {}

  /** A name is valid when it has 1 to 80 characters, one of them visible, and no control one. */
  public static boolean isValidName(String name) {
    if (name == null || name.isEmpty() || name.length() > NAME_MAX) {
      return false;
    }
    boolean visible = false;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isISOControl(c)) {
        return false;
      }
      if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
        visible = true;
      }
    }
    return visible;
  }

  public static boolean isValid(ContactType type, String value) {
    if (type == null || value == null) {
      return false;
    }
    return switch (type) {
      case EMAIL -> isValidEmail(value);
      case PHONE -> isValidPhone(value);
    };
  }

  /** One at sign, a non empty local part, and a domain with a dot and no spaces. */
  private static boolean isValidEmail(String value) {
    if (value.length() < CONTACT_MIN || value.length() > EMAIL_MAX) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
        return false;
      }
    }
    int at = value.indexOf('@');
    if (at <= 0 || at != value.lastIndexOf('@')) {
      return false;
    }
    String domain = value.substring(at + 1);
    return domain.contains(".") && !domain.startsWith(".") && !domain.endsWith(".");
  }

  /** Digits, a leading plus, spaces, hyphens and parentheses, with 3 to 30 characters. */
  private static boolean isValidPhone(String value) {
    if (value.length() < CONTACT_MIN || value.length() > PHONE_MAX) {
      return false;
    }
    int digits = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= '0' && c <= '9') {
        digits++;
      } else if (c == '+') {
        if (i != 0) {
          return false;
        }
      } else if (c != ' ' && c != '-' && c != '(' && c != ')') {
        return false;
      }
    }
    return digits >= PHONE_MIN_DIGITS;
  }
}
