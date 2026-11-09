package com.latifi.api.identity;

import static com.latifi.api.identity.domain.ContactType.EMAIL;
import static com.latifi.api.identity.domain.ContactType.PHONE;
import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.identity.domain.ContactRules;
import org.junit.jupiter.api.Test;

class ContactRulesTest {

  private static String repeat(String text, int count) {
    return text.repeat(count);
  }

  @Test
  void anEmailOf254CharactersIsAccepted() {
    String email = repeat("a", 254 - "@example.org".length()) + "@example.org";
    assertThat(email).hasSize(254);
    assertThat(ContactRules.isValid(EMAIL, email)).isTrue();
  }

  @Test
  void anEmailOf255CharactersIsRejected() {
    String email = repeat("a", 255 - "@example.org".length()) + "@example.org";
    assertThat(email).hasSize(255);
    assertThat(ContactRules.isValid(EMAIL, email)).isFalse();
  }

  @Test
  void aPlainEmailIsAccepted() {
    assertThat(ContactRules.isValid(EMAIL, "ana.quispe+latifi@mail.example.org")).isTrue();
  }

  @Test
  void anEmailWithTwoAtSignsIsRejected() {
    assertThat(ContactRules.isValid(EMAIL, "a@b@example.org")).isFalse();
  }

  @Test
  void anEmailWithNoAtSignIsRejected() {
    assertThat(ContactRules.isValid(EMAIL, "not-an-email")).isFalse();
  }

  @Test
  void anEmailWithNoLocalPartIsRejected() {
    assertThat(ContactRules.isValid(EMAIL, "@example.org")).isFalse();
  }

  @Test
  void anEmailWithNoDotAfterTheAtSignIsRejected() {
    assertThat(ContactRules.isValid(EMAIL, "ana@example")).isFalse();
    assertThat(ContactRules.isValid(EMAIL, "ana.quispe@example")).isFalse();
  }

  @Test
  void anEmailWithADotOnlyAtTheEdgeOfTheDomainIsRejected() {
    assertThat(ContactRules.isValid(EMAIL, "ana@.example")).isFalse();
    assertThat(ContactRules.isValid(EMAIL, "ana@example.")).isFalse();
  }

  @Test
  void anEmailWithSpacesOrControlCharactersIsRejected() {
    assertThat(ContactRules.isValid(EMAIL, "a b@example.org")).isFalse();
    assertThat(ContactRules.isValid(EMAIL, "ana@exa mple.org")).isFalse();
    assertThat(ContactRules.isValid(EMAIL, "ana\n@example.org")).isFalse();
    assertThat(ContactRules.isValid(EMAIL, "ana\u0000@example.org")).isFalse();
  }

  @Test
  void aPhoneWithExactlyThreeDigitsIsAccepted() {
    assertThat(ContactRules.isValid(PHONE, "123")).isTrue();
    assertThat(ContactRules.isValid(PHONE, "1-2 3")).isTrue();
  }

  @Test
  void aPhoneWithTwoDigitsIsRejected() {
    assertThat(ContactRules.isValid(PHONE, "12")).isFalse();
    assertThat(ContactRules.isValid(PHONE, "+1 2")).isFalse();
    assertThat(ContactRules.isValid(PHONE, "(--) 12")).isFalse();
  }

  @Test
  void aPhoneOf30CharactersIsAcceptedAndOf31IsRejected() {
    assertThat(ContactRules.isValid(PHONE, repeat("1", 30))).isTrue();
    assertThat(ContactRules.isValid(PHONE, repeat("1", 31))).isFalse();
  }

  @Test
  void aPhoneWithALeadingPlusAndSeparatorsIsAccepted() {
    assertThat(ContactRules.isValid(PHONE, "+51 (999) 123-456")).isTrue();
  }

  @Test
  void aPhoneWithAPlusInTheMiddleIsRejected() {
    assertThat(ContactRules.isValid(PHONE, "51+999123456")).isFalse();
    assertThat(ContactRules.isValid(PHONE, "++51999123456")).isFalse();
  }

  @Test
  void aPhoneWithLettersIsRejected() {
    assertThat(ContactRules.isValid(PHONE, "call me")).isFalse();
    assertThat(ContactRules.isValid(PHONE, "999 123 45x")).isFalse();
  }

  @Test
  void aMissingTypeOrValueIsRejected() {
    assertThat(ContactRules.isValid(null, "ana@example.org")).isFalse();
    assertThat(ContactRules.isValid(EMAIL, null)).isFalse();
  }

  @Test
  void aNameNeedsOneVisibleCharacterAndAtMost80() {
    assertThat(ContactRules.isValidName("Ana")).isTrue();
    assertThat(ContactRules.isValidName(repeat("a", 80))).isTrue();
    assertThat(ContactRules.isValidName(repeat("a", 81))).isFalse();
    assertThat(ContactRules.isValidName("")).isFalse();
    assertThat(ContactRules.isValidName("   ")).isFalse();
    assertThat(ContactRules.isValidName(null)).isFalse();
  }

  @Test
  void aNameWithControlCharactersIsRejected() {
    assertThat(ContactRules.isValidName("Ana\nQuispe")).isFalse();
    assertThat(ContactRules.isValidName("Ana\u0007")).isFalse();
  }
}
