package cn.ybcase.testing;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestDatabaseGuardTest {
    static final String URL = "jdbc:postgresql://127.0.0.1:15433/ybcase_authowner_test_20261028_abcdef12";
    static final String MARKER = "ybcase-test:0123456789abcdef0123456789abcdef";

    MockEnvironment authorized() {
        return new MockEnvironment().withProperty("YBCASE_TEST_DATABASE_URL", URL)
                .withProperty("YBCASE_TEST_DATABASE_OID", "12345")
                .withProperty("YBCASE_TEST_DATABASE_OWNER", "hip")
                .withProperty("YBCASE_TEST_DATABASE_MARKER", MARKER);
    }

    HikariDataSource source(String url) {
        var source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername("hip");
        source.setPassword("private-test-password");
        return source;
    }

    @Test
    void initializerIsRegisteredForEveryBootTestAndRunsBeforeBeanCreation() {
        assertTrue(SpringFactoriesLoader.loadFactoryNames(
                org.springframework.context.ApplicationContextInitializer.class, getClass().getClassLoader())
                .contains(IsolatedTestDatabaseInitializer.class.getName()));
        try (var context = new GenericApplicationContext()) {
            new IsolatedTestDatabaseInitializer().initialize(context);
            context.registerBean("dataSource", HikariDataSource.class, () -> source(URL));
            context.registerBean("wouldWrite", Object.class, () -> {
                context.getBean(DataSource.class);
                fail("A consumer must not receive an unauthorized datasource");
                return new Object();
            });
            assertThrows(org.springframework.beans.BeansException.class, context::refresh);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"YBCASE_TEST_DATABASE_URL", "YBCASE_TEST_DATABASE_OID",
            "YBCASE_TEST_DATABASE_OWNER", "YBCASE_TEST_DATABASE_MARKER"})
    void missingAuthorizationRejectsBeforeAnyConnection(String key) throws Exception {
        var env = authorized();
        env.getPropertySources().remove("mockProperties");
        var values = new java.util.HashMap<String, Object>();
        values.put("YBCASE_TEST_DATABASE_URL", URL);
        values.put("YBCASE_TEST_DATABASE_OID", "12345");
        values.put("YBCASE_TEST_DATABASE_OWNER", "hip");
        values.put("YBCASE_TEST_DATABASE_MARKER", MARKER);
        values.remove(key);
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("authorization", values));
        try (var driver = mockStatic(DriverManager.class); var dataSource = source(URL)) {
            assertThrows(IllegalStateException.class,
                    () -> new TestDatabaseGuard(env).postProcessBeforeInitialization(dataSource, "dataSource"));
            driver.verifyNoInteractions();
            assertFalse(dataSource.isRunning());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://127.0.0.1:5432/ybcase",
            "jdbc:postgresql://127.0.0.1:5432/ybcase_test",
            "jdbc:postgresql://43.129.201.125:5432/ybcase_authowner_test_20261028_abcdef12",
            "jdbc:postgresql://127.0.0.1:15433/ybcase_authowner_test_20261028_abcdef12?currentSchema=public",
            "jdbc:postgresql://other.example:5432/ybcase_authowner_test_20261028_abcdef12"})
    void realReusablePublicAndAmbiguousTargetsAreRejectedBeforeConnecting(String url) {
        try (var driver = mockStatic(DriverManager.class); var dataSource = source(url)) {
            assertThrows(IllegalStateException.class, () -> new TestDatabaseGuard(
                    authorized().withProperty("YBCASE_TEST_DATABASE_URL", url))
                    .postProcessBeforeInitialization(dataSource, "dataSource"));
            driver.verifyNoInteractions();
            assertFalse(dataSource.isRunning());
        }
    }

    @Test
    void finalDynamicallyOverriddenDatasourceCannotUseEarlierUrlAuthorization() {
        try (var driver = mockStatic(DriverManager.class);
             var dataSource = source(URL.replace("abcdef12", "deadbeef"))) {
            assertThrows(IllegalStateException.class,
                    () -> new TestDatabaseGuard(authorized()).postProcessBeforeInitialization(dataSource, "dataSource"));
            driver.verifyNoInteractions();
            assertFalse(dataSource.isRunning());
        }
    }

    @org.springframework.boot.context.properties.ConfigurationProperties("spring.datasource.hikari")
    public static class BoundTestDataSource extends HikariDataSource {}

    @Test
    void guardObservesConfigurationBindingAfterInitializerAndLaterPropertyOverrides() {
        try (var driver = mockStatic(DriverManager.class); var context = new GenericApplicationContext()) {
            context.setEnvironment(authorized());
            new IsolatedTestDatabaseInitializer().initialize(context);
            org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor.register(context);
            // Like a later DynamicPropertySource: this appears after initializer registration.
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("laterOverride", java.util.Map.of(
                            "spring.datasource.hikari.jdbc-url", URL.replace("abcdef12", "deadbeef"))));
            var actual = new BoundTestDataSource();
            actual.setJdbcUrl(URL);
            actual.setUsername("hip");
            context.registerBean("dataSource", BoundTestDataSource.class, () -> actual);
            assertThrows(org.springframework.beans.BeansException.class, context::refresh);
            assertEquals(URL.replace("abcdef12", "deadbeef"), actual.getJdbcUrl());
            assertFalse(actual.isRunning());
            driver.verifyNoInteractions();
        }
    }

    @Test
    void separateFlywayDatabaseCannotBypassTheAuthorizedPool() {
        try (var driver = mockStatic(DriverManager.class); var dataSource = source(URL)) {
            assertThrows(IllegalStateException.class, () -> new TestDatabaseGuard(authorized()
                    .withProperty("spring.flyway.url", URL.replace("abcdef12", "deadbeef")))
                    .postProcessBeforeInitialization(dataSource, "dataSource"));
            driver.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dataSource", "dataSourceClassName", "jndi", "properties"})
    void alternativePoolConnectionSettingsCannotBypassCheckedJdbcUrl(String setting) {
        try (var driver = mockStatic(DriverManager.class); var dataSource = source(URL)) {
            switch (setting) {
                case "dataSource" -> dataSource.setDataSource(mock(DataSource.class));
                case "dataSourceClassName" -> dataSource.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
                case "jndi" -> dataSource.setDataSourceJNDI("java:comp/env/otherDatabase");
                case "properties" -> dataSource.addDataSourceProperty("user", "other_owner");
                default -> fail("Unknown alternate pool configuration");
            }
            assertThrows(IllegalStateException.class,
                    () -> new TestDatabaseGuard(authorized()).postProcessBeforeInitialization(dataSource, "dataSource"));
            driver.verifyNoInteractions();
            assertFalse(dataSource.isRunning());
        }
    }

    @ParameterizedTest
    @CsvSource({"1,another_database", "2,54321", "3,other_owner", "4,wrong-marker", "5,other_login"})
    void actualDatabaseIdentityMustMatchEveryAuthorizationField(int field, String replacement) throws Exception {
        verifyIdentity(field, replacement, false);
    }

    @Test
    void matchingIdentityIsReadOnlyBeforePoolInitializationAndAllowsTheDatasource() throws Exception {
        verifyIdentity(0, "", true);
    }

    void verifyIdentity(int differentField, String replacement, boolean allowed) throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        String[] expected = {"ybcase_authowner_test_20261028_abcdef12", "12345", "hip", MARKER, "hip"};
        for (int index = 0; index < expected.length; index++) {
            when(rows.getString(index + 1)).thenReturn(index + 1 == differentField ? replacement : expected[index]);
        }
        try (var driver = mockStatic(DriverManager.class); var dataSource = source(URL)) {
            driver.when(() -> DriverManager.getConnection(eq(URL), any(Properties.class))).thenReturn(connection);
            if (allowed) assertSame(dataSource, new TestDatabaseGuard(authorized())
                    .postProcessBeforeInitialization(dataSource, "dataSource"));
            else assertThrows(IllegalStateException.class,
                    () -> new TestDatabaseGuard(authorized()).postProcessBeforeInitialization(dataSource, "dataSource"));
            driver.verify(() -> DriverManager.getConnection(eq(URL), argThat((Properties properties) ->
                    "always".equals(properties.getProperty("readOnlyMode"))
                            && "-c default_transaction_read_only=on".equals(properties.getProperty("options")))));
            verify(connection).setReadOnly(true);
            verify(connection).setAutoCommit(false);
            verify(statement, never()).executeUpdate(anyString());
            verify(connection).close();
            assertFalse(dataSource.isRunning(), "Pool initialization SQL must not run before authorization");
        }
    }
}
