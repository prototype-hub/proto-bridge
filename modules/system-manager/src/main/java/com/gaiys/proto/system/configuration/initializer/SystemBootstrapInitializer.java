package com.gaiys.proto.system.configuration.initializer;

import com.gaiys.proto.system.service.SystemBootstrapService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 在数据库结构与注释准备完成后，同步完成权限及内置角色初始化，再创建管理员和菜单。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class SystemBootstrapInitializer implements ApplicationRunner {
    private final SystemBootstrapService initialization;

    @Override
    public void run(ApplicationArguments args) {
        // 只在同步启动边界等待；失败终止启动，避免空授权平台对外提供登录服务。
        initialization.initialize().block(Duration.ofMinutes(2));
    }
}
