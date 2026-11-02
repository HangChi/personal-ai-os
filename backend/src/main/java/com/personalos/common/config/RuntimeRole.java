package com.personalos.common.config;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** combined 保留单进程兼容模式；部署时显式选择 api/index/review/delivery。 */
@Component
public class RuntimeRole {
    private final String role;
    public RuntimeRole(@Value("${app.runtime.role:combined}") String role) {
        if (!Set.of("combined","api","index","review","delivery").contains(role)) throw new IllegalArgumentException("Invalid runtime role");
        this.role=role;
    }
    public boolean allows(String task) { return "combined".equals(role) || role.equals(task); }
    public boolean worker() { return !allows("api"); }
    public String name() { return role; }
}
