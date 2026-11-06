import org.flywaydb.core.Flyway;

/** 034 功能的独立迁移验证，不改写 033 的固定 42 迁移 oracle。 */
public final class WorkspaceFlyway {
    public static void main(String[] args) {
        String host=System.getenv("GO_TEST_POSTGRES_HOST");
        String db=System.getenv("GO_TEST_POSTGRES_DB");
        if(!"127.0.0.1".equals(host)||!"personalos_go_test".equals(db)) throw new IllegalArgumentException("只允许临时功能库");
        var config=Flyway.configure().dataSource("jdbc:postgresql://"+host+":"+System.getenv("GO_TEST_POSTGRES_PORT")+"/"+db,
            System.getenv("GO_TEST_POSTGRES_USER"),System.getenv("GO_TEST_POSTGRES_PASSWORD"))
            .locations("filesystem:"+args[0]);
        long expected;
        try(var files=java.nio.file.Files.list(java.nio.file.Path.of(args[0]))) { expected=files.filter(p->p.getFileName().toString().matches("V[0-9]+__.*\\.sql")).count(); } catch(java.io.IOException ex) { throw new IllegalStateException(ex); }
        int previous=0;
        if(args.length>1) {
            int version=Integer.parseInt(args[1]);
            if(version<1||version>=expected)throw new IllegalArgumentException("升级起始版本须在当前迁移之前");
            var baseline=config.target(Integer.toString(version)).load();
            previous=baseline.migrate().migrationsExecuted;
            baseline.validate();
            if(previous!=version)throw new AssertionError("升级起始库不完整");
            config.target("latest");
        }
        var flyway=config.load();
        var result=flyway.migrate();
        flyway.validate();
        if(previous+result.migrationsExecuted!=expected || flyway.migrate().migrationsExecuted!=0) throw new AssertionError("期望完整迁移或升级，且再次执行为0");
        System.out.println("功能独立 Flyway 迁移/升级、validate、幂等重放通过（起始迁移数="+previous+"）");
    }
}
