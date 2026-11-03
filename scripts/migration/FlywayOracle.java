import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.StringResource;

/** 只执行基线 Flyway 的校验和，或脚本新建临时集群的完整迁移；不启动应用。 */
public final class FlywayOracle {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String encoded;
                while ((encoded = input.readLine()) != null) {
                    var sql = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                    System.out.println(ChecksumCalculator.calculate(new StringResource(sql)));
                }
            }
            return;
        }
        if (args.length != 2 || !"migrate".equals(args[0]) ||
                !"disposable".equals(System.getenv("GO_TEST_POSTGRES")) ||
                !"127.0.0.1".equals(System.getenv("GO_TEST_POSTGRES_HOST")) ||
                !"personalos_go_test".equals(System.getenv("GO_TEST_POSTGRES_DB")) ||
                !"go_fixture".equals(System.getenv("GO_TEST_POSTGRES_USER"))) {
            throw new IllegalArgumentException("仅允许脚本新建的临时 Flyway 数据库");
        }
        int port = Integer.parseInt(System.getenv("GO_TEST_POSTGRES_PORT"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("测试端口无效");
        var properties = new java.util.Properties();
        properties.setProperty("user", "go_fixture");
        properties.setProperty("password", System.getenv("GO_TEST_POSTGRES_PASSWORD"));
        properties.setProperty("sslmode", System.getenv("GO_TEST_POSTGRES_SSL_MODE"));
        if ("verify-full".equals(properties.getProperty("sslmode"))) {
            properties.setProperty("sslrootcert", System.getenv("GO_TEST_POSTGRES_ROOT_CERT"));
        }
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "30");
        var source = new org.postgresql.ds.PGSimpleDataSource();
        source.setURL("jdbc:postgresql://127.0.0.1:" + port + "/personalos_go_test");
        for (var key : properties.stringPropertyNames()) source.setProperty(key, properties.getProperty(key));
        var flyway = Flyway.configure().dataSource(source)
                .locations("filesystem:" + Path.of(args[1]).toAbsolutePath())
                .defaultSchema("public").baselineOnMigrate(true).cleanDisabled(true).load();
        if (flyway.migrate().migrationsExecuted != 42) throw new IllegalStateException("未执行全部42原迁移");
        flyway.validate();
        if (flyway.migrate().migrationsExecuted != 0) throw new IllegalStateException("第二次执行未保持幂等");
        System.out.println("真实 Flyway 42 迁移/validate/二次零执行通过");
    }
}
