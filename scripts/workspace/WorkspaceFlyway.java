import org.flywaydb.core.Flyway;

/** 034 功能的独立迁移验证，不改写 033 的固定 42 迁移 oracle。 */
public final class WorkspaceFlyway {
    public static void main(String[] args) {
        String host=System.getenv("GO_TEST_POSTGRES_HOST");
        String db=System.getenv("GO_TEST_POSTGRES_DB");
        if(!"127.0.0.1".equals(host)||!"personalos_go_test".equals(db)) throw new IllegalArgumentException("只允许临时功能库");
        var flyway=Flyway.configure().dataSource("jdbc:postgresql://"+host+":"+System.getenv("GO_TEST_POSTGRES_PORT")+"/"+db,
            System.getenv("GO_TEST_POSTGRES_USER"),System.getenv("GO_TEST_POSTGRES_PASSWORD"))
            .locations("filesystem:"+args[0]).load();
        var result=flyway.migrate();
        flyway.validate();
        if(result.migrationsExecuted!=43 || flyway.migrate().migrationsExecuted!=0) throw new AssertionError("期望全新库43迁移且再次执行为0");
        System.out.println("034 独立 Flyway 43 迁移、validate、幂等重放通过");
    }
}
