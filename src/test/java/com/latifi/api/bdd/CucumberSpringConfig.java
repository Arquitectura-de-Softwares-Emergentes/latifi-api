package com.latifi.api.bdd;

import com.latifi.api.support.LatifiIntegrationTest;
import io.cucumber.spring.CucumberContextConfiguration;

/** Ties Cucumber to the same Spring context that the other integration tests use. */
@CucumberContextConfiguration
@LatifiIntegrationTest
public class CucumberSpringConfig {}
