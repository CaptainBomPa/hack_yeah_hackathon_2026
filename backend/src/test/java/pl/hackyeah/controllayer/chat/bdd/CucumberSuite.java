package pl.hackyeah.controllayer.chat.bdd;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Self-testing suite (CRITERIA: "positive and negative cases for the controls") — see {@code
 * backend/README.md} §"Self-testing suite" for how to run it. One command: {@code ./gradlew
 * test} runs this alongside the regular JUnit tests, because Cucumber runs as a JUnit Platform
 * engine. {@code .feature} files live under {@code src/test/resources/features}; step
 * definitions (glue) live in this package. Reports: {@code build/reports/cucumber/report.html}
 * (always works) or {@code allure serve build/allure-results} (nicer, needs the Allure CLI).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "pl.hackyeah.controllayer.chat.bdd")
@ConfigurationParameter(
        key = PLUGIN_PROPERTY_NAME,
        value = "pretty, html:build/reports/cucumber/report.html, "
                + "io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm")
public class CucumberSuite {}
