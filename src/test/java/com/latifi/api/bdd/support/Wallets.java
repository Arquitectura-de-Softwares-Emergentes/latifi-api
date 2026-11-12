package com.latifi.api.bdd.support;

import io.cucumber.spring.ScenarioScope;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The wallets that a scenario generated and the session tokens they obtained, by the short name the
 * scenario gave them. The profile steps and the reputation steps share it.
 */
@Component
@ScenarioScope
public class Wallets {

  private final Map<String, TestWallet> wallets = new HashMap<>();
  private final Map<String, String> tokens = new HashMap<>();

  public Map<String, TestWallet> wallets() {
    return wallets;
  }

  public Map<String, String> tokens() {
    return tokens;
  }
}
