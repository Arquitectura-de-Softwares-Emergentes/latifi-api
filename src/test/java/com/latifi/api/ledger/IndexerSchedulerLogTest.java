package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.latifi.api.ledger.application.IndexerService;
import com.latifi.api.ledger.domain.ChainRpcErrorException;
import com.latifi.api.ledger.infrastructure.IndexerScheduler;
import com.latifi.api.ledger.infrastructure.SafeErrorText;
import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A failed cycle says why it failed, and never prints a node URL or its key. */
class IndexerSchedulerLogTest {

  private static final String KEY = "s3cr3tK3y123";
  private static final String URL = "https://polygon-amoy.example.io/v3/" + KEY;

  private final IndexerService indexer = mock(IndexerService.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void captureTheLog() {
    logger = (Logger) LoggerFactory.getLogger(IndexerScheduler.class);
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(appender);
  }

  private String failureLine() {
    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aNodeErrorShowsItsMessage() throws Exception {
    when(indexer.runOnce())
        .thenThrow(
            new ChainRpcErrorException("eth_getLogs returned error -32005: rate limited", -32005));

    new IndexerScheduler(indexer).tick();

    assertThat(failureLine())
        .contains("ChainRpcErrorException")
        .contains("eth_getLogs returned error -32005: rate limited");
  }

  @Test
  void aDatabaseErrorShowsItsMessageAndCause() throws Exception {
    when(indexer.runOnce())
        .thenThrow(
            new IllegalStateException(
                "projection failed", new SQLException("duplicate key value violates constraint")));

    new IndexerScheduler(indexer).tick();

    assertThat(failureLine())
        .contains("IllegalStateException: projection failed")
        .contains("SQLException: duplicate key value violates constraint");
  }

  @Test
  void aUrlAndItsKeyNeverReachTheLog() throws Exception {
    when(indexer.runOnce())
        .thenThrow(
            new IllegalStateException(
                "call to " + URL + " failed apikey=" + KEY, new IOException("connect " + URL)));

    new IndexerScheduler(indexer).tick();

    assertThat(failureLine())
        .contains("IllegalStateException")
        .contains("IOException")
        .doesNotContain(KEY)
        .doesNotContain("polygon-amoy.example.io");
  }

  @Test
  void theSanitizerStopsAtFiveCausesAndKeepsOneLine() {
    Throwable chain = new RuntimeException("level 0\nsecond line");
    for (int level = 1; level < 10; level++) {
      chain = new RuntimeException("level " + level, chain);
    }

    String text = SafeErrorText.of(chain);

    assertThat(text).doesNotContain("\n").contains("level 9").contains("level 5");
    assertThat(text).doesNotContain("level 4");
  }
}
