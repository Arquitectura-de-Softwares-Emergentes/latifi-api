package com.latifi.api.support;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Starts one {@link FakeChainNode} per Spring context and points the chain properties at it before
 * the configuration classes are parsed. The RPC URL has to be known that early, because the beans
 * that need a node are conditional on it, and a {@code DynamicPropertyRegistrar} runs too late for
 * a condition.
 *
 * <p>The values are defaults. A test that sets the same key with {@code @TestPropertySource} wins.
 */
public class FakeChainNodeInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final String SOURCE_NAME = "fakeChainNodeDefaults";
  private static final String INLINED_TEST_PROPERTIES = "Inlined Test Properties";

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    FakeChainNode node = new FakeChainNode();
    context.getBeanFactory().registerSingleton("fakeChainNode", node);
    context.addApplicationListener(
        event -> {
          if (event instanceof ContextClosedEvent closed
              && closed.getApplicationContext() == context) {
            node.stop();
          }
        });

    ConfigurableEnvironment environment = context.getEnvironment();
    PropertySource<?> defaults =
        new MapPropertySource(
            SOURCE_NAME,
            Map.of(
                "latifi.chain.rpc-url", node.url(),
                "latifi.chain.loan-book-address", ChainFixtures.LOAN_BOOK,
                "latifi.indexer.start-block", "1",
                "latifi.indexer.batch-size", "10",
                "latifi.indexer.enabled", "false"));
    if (environment.getPropertySources().contains(INLINED_TEST_PROPERTIES)) {
      environment.getPropertySources().addAfter(INLINED_TEST_PROPERTIES, defaults);
    } else {
      environment.getPropertySources().addFirst(defaults);
    }
  }
}
