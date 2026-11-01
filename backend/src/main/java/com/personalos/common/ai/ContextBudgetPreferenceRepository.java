package com.personalos.common.ai;

import java.util.OptionalInt;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 本地单用户的预算偏好；保存原始选择，模型窗口只约束实际使用值。 */
@Repository
@DependsOnDatabaseInitialization
public class ContextBudgetPreferenceRepository {
    private final JdbcTemplate jdbc;

    public ContextBudgetPreferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OptionalInt findBudgetTokens() {
        var values = jdbc.queryForList("SELECT budget_tokens FROM chat_context_preference WHERE id = 1", Integer.class);
        return values.isEmpty() ? OptionalInt.empty() : OptionalInt.of(values.getFirst());
    }

    public void saveBudgetTokens(int tokens) {
        jdbc.update("""
                INSERT INTO chat_context_preference (id, budget_tokens) VALUES (1, ?)
                ON CONFLICT (id) DO UPDATE SET budget_tokens = EXCLUDED.budget_tokens
                """, tokens);
    }
}
