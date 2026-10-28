package com.personalos.common.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把数据库里的模型配置应用到运行期。
 *
 * <h2>为什么用 {@code ApplicationRunner} 而不是 {@code @PostConstruct}</h2>
 * 要读到 Flyway 迁移后的数据，必须等数据源与迁移都完成 ——
 * {@code @PostConstruct} 在 Bean 初始化阶段执行，那时 Flyway 可能还没跑完，
 * 表不存在会直接抛异常让应用起不来。{@code ApplicationRunner} 在容器就绪后执行，
 * 时序上安全。
 *
 * <h2>为什么单独一个类而不是塞进 {@code ModelConfigService}</h2>
 * 服务类被大量测试直接 mock 或 new，加一个 Spring Boot 启动接口会让它
 * 依赖 Web 层概念。拆开后服务类保持纯粹，「何时调用」由这个薄壳决定。
 */
@Component
public class ModelConfigStartupApplier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigStartupApplier.class);

    private final ModelConfigService modelConfigService;

    public ModelConfigStartupApplier(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            modelConfigService.applyOnStartup();
        } catch (RuntimeException cause) {
            // 启动期不能因为「模型配置有问题」就整体失败：那是把一个可修的配置错误
            // 升级成「应用不可用」。记录后继续，用户可在设置页改回来。
            log.warn("启动期应用模型配置失败，沿用自动配置的模型：{}", cause.toString());
        }
    }
}
