package cn.ybcase.testing;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.URI;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Refuse unowned databases before Flyway, SQL initialization or application runners use a datasource. */
public final class TestDatabaseGuard implements BeanPostProcessor, Ordered {
    private final Environment environment;

    public TestDatabaseGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        // ConfigurationPropertiesBindingPostProcessor is PriorityOrdered. Check its final Hikari values.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource)) return bean;
        if (!(bean instanceof HikariDataSource dataSource)) {
            throw refused("Unsupported test datasource; explicit guarded Hikari configuration required");
        }
        if (dataSource.getDataSource() != null || dataSource.getDataSourceClassName() != null
                || dataSource.getDataSourceJNDI() != null || !dataSource.getDataSourceProperties().isEmpty()
                || (dataSource.getDriverClassName() != null
                    && !"org.postgresql.Driver".equals(dataSource.getDriverClassName()))) {
            throw refused("Alternative datasource or driver properties could bypass the authorized JDBC URL");
        }
        String url = required("YBCASE_TEST_DATABASE_URL");
        String oid = required("YBCASE_TEST_DATABASE_OID");
        String owner = required("YBCASE_TEST_DATABASE_OWNER");
        String marker = required("YBCASE_TEST_DATABASE_MARKER");
        if (!oid.matches("[1-9][0-9]{0,9}") || !owner.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || !marker.matches("ybcase-test:[a-f0-9]{32}")) {
            throw refused("Invalid explicit database ownership evidence");
        }
        String database = databaseName(url);
        if (!url.equals(dataSource.getJdbcUrl()) || !owner.equals(dataSource.getUsername())) {
            throw refused("Final datasource differs from explicitly authorized URL or owner");
        }
        String flywayUrl = environment.getProperty("spring.flyway.url");
        String flywayUser = environment.getProperty("spring.flyway.user");
        if ((flywayUrl != null && !url.equals(flywayUrl))
                || (flywayUser != null && !owner.equals(flywayUser))) {
            throw refused("Separate Flyway datasource differs from the authorized test database");
        }
        Properties properties = new Properties();
        properties.setProperty("user", owner);
        if (dataSource.getPassword() != null) properties.setProperty("password", dataSource.getPassword());
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "5");
        properties.setProperty("readOnlyMode", "always");
        properties.setProperty("options", "-c default_transaction_read_only=on");
        // Do not call the pool: Hikari connectionInitSql must not run before identity verification.
        try (var connection = DriverManager.getConnection(url, properties)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         select current_database(),d.oid::text,pg_get_userbyid(d.datdba),
                           shobj_description(d.oid,'pg_database'),current_user
                         from pg_catalog.pg_database d where d.datname=current_database()
                         """)) {
                if (!rows.next() || !database.equals(rows.getString(1)) || !oid.equals(rows.getString(2))
                        || !owner.equals(rows.getString(3)) || !marker.equals(rows.getString(4))
                        || !owner.equals(rows.getString(5)) || rows.next()) {
                    throw refused("Actual database name, OID, owner or authorization marker differs");
                }
            }
            connection.rollback();
        } catch (SQLException error) {
            throw refused("Database ownership could not be verified with a read-only connection");
        }
        return bean;
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw refused("Missing explicit isolated database authorization");
        return value;
    }

    private static String databaseName(String url) {
        try {
            if (!url.startsWith("jdbc:postgresql://")) throw refused("Invalid isolated test database URL");
            URI parsed = new URI(url.substring(5));
            String host = parsed.getHost();
            if (host == null || !(host.equals("localhost") || host.matches("[0-9.]+"))
                    || parsed.getPort() < 1 || parsed.getPort() > 65535
                    || parsed.getRawQuery() != null || parsed.getRawFragment() != null
                    || parsed.getRawUserInfo() != null
                    || !parsed.getRawPath().matches("/ybcase_authowner_test_[0-9]{8}_[a-f0-9]{8}")) {
                throw refused("Only an explicit local random test database URL is permitted");
            }
            InetAddress address = InetAddress.getByName(host);
            if (!(address.isLoopbackAddress() || address.isSiteLocalAddress())) {
                throw refused("Remote public databases cannot be used by tests");
            }
            return parsed.getPath().substring(1);
        } catch (java.net.URISyntaxException | java.net.UnknownHostException error) {
            throw refused("Invalid isolated test database URL");
        }
    }

    private static IllegalStateException refused(String reason) {
        return new IllegalStateException("Isolated test database refused: " + reason);
    }
}
