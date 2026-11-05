package com.personalos.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInfo;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContextManager;

/**
 * Close a class-owned Spring context before the Testcontainers extension stops its containers.
 *
 * <p>User {@code @AfterAll} methods run before extension {@code AfterAllCallback}s. Evict the
 * context through Spring's cache API, rather than leaving a closed instance cached for another
 * test or postponing dozens of connection-pool shutdowns until the JVM exit hook.
 */
public abstract class ContainerContextTest {
    @AfterAll
    protected static void closeContainerContext(TestInfo testInfo) {
        var testClass = testInfo.getTestClass().orElseThrow();
        new TestContextManager(testClass).getTestContext()
                .markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
    }
}
