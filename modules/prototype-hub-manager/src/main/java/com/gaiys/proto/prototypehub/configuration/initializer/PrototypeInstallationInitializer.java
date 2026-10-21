package com.gaiys.proto.prototypehub.configuration.initializer;

import com.gaiys.proto.prototypehub.service.PrototypeInstallationService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** EasyORM 建表和描述同步之后、应用就绪之前初始化持久化部署身份。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
public class PrototypeInstallationInitializer implements ApplicationRunner {
    private final PrototypeInstallationService installations;

    @Override
    public void run(ApplicationArguments args) {
        // 仅同步启动边界等待，业务响应式链路不阻塞。
        installations.initialize().block(Duration.ofMinutes(2));
    }
}
