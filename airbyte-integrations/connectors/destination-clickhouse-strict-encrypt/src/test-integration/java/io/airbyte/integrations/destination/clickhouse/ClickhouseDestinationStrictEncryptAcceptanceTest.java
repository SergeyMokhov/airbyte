/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.clickhouse;

import static java.time.temporal.ChronoUnit.SECONDS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.factory.DataSourceFactory;
import io.airbyte.db.factory.DatabaseDriver;
import io.airbyte.db.jdbc.DefaultJdbcDatabase;
import io.airbyte.db.jdbc.JdbcDatabase;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.destination.ExtendedNameTransformer;
import io.airbyte.integrations.standardtest.destination.DestinationAcceptanceTest;
import io.airbyte.integrations.standardtest.destination.comparator.TestDataComparator;
import io.airbyte.integrations.util.HostPortResolver;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

public class ClickhouseDestinationStrictEncryptAcceptanceTest extends DestinationAcceptanceTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClickhouseDestinationStrictEncryptAcceptanceTest.class);

  public static final Integer HTTP_PORT = 8123;
  public static final Integer NATIVE_PORT = 9000;
  public static final Integer HTTPS_PORT = 8443;
  public static final Integer NATIVE_SECURE_PORT = 9440;
  private static final String DB_NAME = "default";
  private static final String USER_NAME = "default";
  private final ExtendedNameTransformer namingResolver = new ExtendedNameTransformer();
  private GenericContainer db;

  private static JdbcDatabase getDatabase(final JsonNode config) {
    final String jdbcStr = String.format(DatabaseDriver.CLICKHOUSE.getUrlFormatString() + "?sslmode=none",
        ClickhouseDestination.HTTPS_PROTOCOL,
        config.get(JdbcUtils.HOST_KEY).asText(),
        config.get(JdbcUtils.PORT_KEY).asInt(),
        config.get(JdbcUtils.DATABASE_KEY).asText());
    return new DefaultJdbcDatabase(DataSourceFactory.create(
        config.get(JdbcUtils.USERNAME_KEY).asText(),
        config.has(JdbcUtils.PASSWORD_KEY) ? config.get(JdbcUtils.PASSWORD_KEY).asText() : null,
        ClickhouseDestination.DRIVER_CLASS,
        jdbcStr));
  }

  @Override
  protected String getImageName() {
    return "airbyte/destination-clickhouse-strict-encrypt:dev";
  }

  @Override
  protected boolean supportsNormalization() {
    return false;
  }

  @Override
  protected boolean supportsDBT() {
    return false;
  }

  @Override
  protected boolean implementsNamespaces() {
    return true;
  }

  @Override
  protected TestDataComparator getTestDataComparator() {
    return new ClickhouseTestDataComparator();
  }

  @Override
  protected boolean supportBasicDataTypeTest() {
    return true;
  }

  @Override
  protected boolean supportArrayDataTypeTest() {
    return true;
  }

  @Override
  protected boolean supportObjectDataTypeTest() {
    return true;
  }

  @Override
  protected String getDefaultSchema(final JsonNode config) {
    if (config.get(JdbcUtils.DATABASE_KEY) == null) {
      return null;
    }
    return config.get(JdbcUtils.DATABASE_KEY).asText();
  }

  @Override
  protected JsonNode getConfig() {
    // Note: ClickHouse official JDBC driver uses HTTP protocol, its default port is 8123
    // dbt clickhouse adapter uses native protocol, its default port is 9000
    // Since we disabled normalization and dbt test, we only use the JDBC port here.
    return Jsons.jsonNode(ImmutableMap.builder()
        .put(JdbcUtils.HOST_KEY, HostPortResolver.resolveIpAddress(db))
        .put(JdbcUtils.PORT_KEY, HTTPS_PORT)
        .put(JdbcUtils.DATABASE_KEY, DB_NAME)
        .put(JdbcUtils.USERNAME_KEY, USER_NAME)
        .put(JdbcUtils.PASSWORD_KEY, "")
        .put(JdbcUtils.SCHEMA_KEY, DB_NAME)
        .build());
  }

  @Override
  protected JsonNode getFailCheckConfig() {
    final JsonNode clone = Jsons.clone(getConfig());
    ((ObjectNode) clone).put("password", "wrong password").put(JdbcUtils.SSL_KEY, false);
    return clone;
  }

  @Override
  protected List<JsonNode> retrieveNormalizedRecords(final TestDestinationEnv testEnv,
                                                     final String streamName,
                                                     final String namespace)
      throws Exception {
    return retrieveRecordsFromTable(namingResolver.getIdentifier(streamName), namespace);
  }

  @Override
  protected List<JsonNode> retrieveRecords(final TestDestinationEnv testEnv,
                                           final String streamName,
                                           final String namespace,
                                           final JsonNode streamSchema)
      throws Exception {
    return retrieveRecordsFromTable(namingResolver.getRawTableName(streamName), namespace)
        .stream()
        .map(r -> Jsons.deserialize(r.get(JavaBaseConstants.COLUMN_NAME_DATA).asText()))
        .collect(Collectors.toList());
  }

  private List<JsonNode> retrieveRecordsFromTable(final String tableName, final String schemaName) throws SQLException {
    final JdbcDatabase jdbcDB = getDatabase(getConfig());
    final String query = String.format("SELECT * FROM %s.%s ORDER BY %s ASC", schemaName, tableName, JavaBaseConstants.COLUMN_NAME_EMITTED_AT);
    return jdbcDB.queryJsons(query);
  }

  @Override
  protected List<String> resolveIdentifier(final String identifier) {
    final List<String> result = new ArrayList<>();
    final String resolved = namingResolver.getIdentifier(identifier);
    result.add(identifier);
    result.add(resolved);
    if (!resolved.startsWith("\"")) {
      result.add(resolved.toLowerCase());
      result.add(resolved.toUpperCase());
    }
    return result;
  }

  @Override
  protected void setup(final TestDestinationEnv testEnv) {
    db = new GenericContainer<>(new ImageFromDockerfile("clickhouse-test")
        .withFileFromClasspath("Dockerfile", "docker/Dockerfile")
        .withFileFromClasspath("clickhouse_certs.sh", "docker/clickhouse_certs.sh"))
            .withEnv("TZ", "UTC")
            .withExposedPorts(HTTP_PORT, NATIVE_PORT, HTTPS_PORT, NATIVE_SECURE_PORT)
            .withClasspathResourceMapping("ssl_ports.xml", "/etc/clickhouse-server/config.d/ssl_ports.xml", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/ping").forPort(HTTP_PORT)
                .forStatusCode(200).withStartupTimeout(Duration.of(60, SECONDS)));

    db.start();

    LOGGER.info(String.format("Clickhouse server container port mapping: %d -> %d, %d -> %d, %d -> %d, %d -> %d",
        HTTP_PORT, db.getMappedPort(HTTP_PORT),
        HTTPS_PORT, db.getMappedPort(HTTPS_PORT),
        NATIVE_PORT, db.getMappedPort(NATIVE_PORT),
        NATIVE_SECURE_PORT, db.getMappedPort(NATIVE_SECURE_PORT)));
  }

  @Override
  protected void tearDown(final TestDestinationEnv testEnv) {
    db.stop();
    db.close();
  }

  /**
   * The SQL script generated by old version of dbt in 'test' step isn't compatible with ClickHouse,
   * so we skip this test for now.
   *
   * Ref: https://github.com/dbt-labs/dbt-core/issues/3905
   *
   * @throws Exception
   */
  @Disabled
  public void testCustomDbtTransformations() throws Exception {
    super.testCustomDbtTransformations();
  }

  @Disabled
  public void testCustomDbtTransformationsFailure() throws Exception {}

  /**
   * The normalization container needs native port, while destination container needs HTTP port, we
   * can't inject the port switch statement into DestinationAcceptanceTest.runSync() method for this
   * test, so we skip it.
   *
   * @throws Exception
   */
  @Disabled
  public void testIncrementalDedupeSync() throws Exception {
    super.testIncrementalDedupeSync();
  }

  /**
   * The normalization container needs native port, while destination container needs HTTP port, we
   * can't inject the port switch statement into DestinationAcceptanceTest.runSync() method for this
   * test, so we skip it.
   *
   * @throws Exception
   */
  @Disabled
  public void testSyncWithNormalization(final String messagesFilename, final String catalogFilename) throws Exception {
    super.testSyncWithNormalization(messagesFilename, catalogFilename);
  }

  @Disabled
  public void specNormalizationValueShouldBeCorrect() throws Exception {
    super.specNormalizationValueShouldBeCorrect();
  }

}
