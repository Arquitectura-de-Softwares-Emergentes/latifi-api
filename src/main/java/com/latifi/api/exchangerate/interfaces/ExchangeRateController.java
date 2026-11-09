package com.latifi.api.exchangerate.interfaces;

import com.latifi.api.exchangerate.application.RateService;
import com.latifi.api.exchangerate.application.RateService.Converted;
import com.latifi.api.exchangerate.application.RateService.Quote;
import com.latifi.api.exchangerate.domain.Conversion;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exchange rate endpoints. The {@code /api/v1} prefix comes from WebConfig and both routes need a
 * valid token. Parameters arrive as text, so a malformed value answers 400 VALIDATION_FAILED from
 * the service. A missing parameter answers the same code through the framework handler. The mobile
 * app calls these routes and never the rate provider.
 */
@RestController
@RequestMapping("/exchange-rate")
public class ExchangeRateController {

  private static final String BASE = "USD";

  private final RateService rates;

  public ExchangeRateController(RateService rates) {
    this.rates = rates;
  }

  @GetMapping("/convert")
  public ConvertResponse convert(@RequestParam String amount, @RequestParam String currency) {
    Converted converted = rates.convert(amount, currency);
    return new ConvertResponse(
        converted.amount().toString(),
        converted.currency(),
        Conversion.formatValue(converted.value()),
        Conversion.formatRate(converted.rate()),
        converted.asOf(),
        converted.stale());
  }

  @GetMapping("/rates")
  public List<RateQuoteDto> list() {
    return rates.list().stream().map(RateQuoteDto::from).toList();
  }

  /** The ConvertResponse of the contract. Numbers are plain decimal strings. */
  public record ConvertResponse(
      String amount, String currency, String value, String rate, Instant asOf, boolean stale) {}

  /** The RateQuote of the contract. */
  public record RateQuoteDto(String base, String quote, String rate, Instant asOf, boolean stale) {

    static RateQuoteDto from(Quote quote) {
      return new RateQuoteDto(
          BASE, quote.currency(), Conversion.formatRate(quote.rate()), quote.asOf(), quote.stale());
    }
  }
}
