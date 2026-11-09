package com.latifi.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The README names every environment variable that {@code application.yml} reads. */
class ReadmeEnvTest {

  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\$\\{((?:LATIFI_|SPRING_DATASOURCE_)[A-Z0-9_]+)");

  @Test
  void readmeMentionsEveryVariableOfApplicationYml() throws IOException {
    String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
    String readme = Files.readString(Path.of("README.md"));
    Set<String> variables = new TreeSet<>();
    Matcher matcher = PLACEHOLDER.matcher(yaml);
    while (matcher.find()) {
      variables.add(matcher.group(1));
    }
    assertThat(variables).as("variables found in application.yml").isNotEmpty();

    Set<String> undocumented = new TreeSet<>();
    for (String variable : variables) {
      if (!readme.contains(variable)) {
        undocumented.add(variable);
      }
    }
    assertThat(undocumented).as("variables missing from README.md").isEmpty();
  }
}
