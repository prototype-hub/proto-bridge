package com.gaiys.proto.prototypehub.storage.work;

import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 原型文件系统操作、ZIP 解析和密码散列的阻塞执行边界。
 *
 * <p>使用有界专用调度器隔离 WebFlux 线程；调用方组合返回的 Mono，Spring 销毁组件时释放调度器。
 */
@Component
public class FileWork implements AutoCloseable {
    private final Scheduler scheduler = Schedulers.newBoundedElastic(4, 64, "prototype-hub-files");

    public <T> Mono<T> call(Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(scheduler);
    }

    public Mono<Void> run(CheckedRunnable operation) {
        return call(() -> {
                    operation.run();
                    return true;
                })
                .then();
    }

    /** 可抛出检查异常的同步操作，仅由专用调度器调用。 */
    public interface CheckedRunnable {
        /**
         * 执行一次同步操作。
         *
         * @throws Exception 操作失败时向返回的 Mono 传播，由调用方决定恢复方式
         */
        void run() throws Exception;
    }

    @Override
    public void close() {
        scheduler.dispose();
    }
}
