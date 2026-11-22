package com.gaiys.proto.notification;

import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** SMTP 专用阻塞边界：四个线程、全局 64 个等待任务，不占用文件或 BCrypt 工作池。 */
@Slf4j
public class SmtpWork implements AutoCloseable {
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicInteger threadIds = new AtomicInteger();
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(
                    4,
                    4,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(64),
                    operation -> {
                        var thread =
                                new Thread(
                                        operation,
                                        "notification-smtp-" + threadIds.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    },
                    (operation, pool) -> {
                        rejected.incrementAndGet();
                        throw new RejectedExecutionException("SMTP worker unavailable");
                    });
    private final Scheduler scheduler = Schedulers.fromExecutorService(executor);
    private ObjectName registeredName;

    /** 在订阅时提交一次阻塞 SMTP 调用；取消和资源关闭沿 Reactor 标准生命周期处理。 */
    public <T> Mono<T> call(Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(scheduler);
    }

    @PostConstruct
    void registerMonitor() {
        try {
            var name = new ObjectName("org.jetlinks:type=NotificationSmtp,name=shared");
            var server = ManagementFactory.getPlatformMBeanServer();
            // 多应用上下文共享平台 MBean 时不夺取已有注册，也不影响邮件主流程。
            if (server.isRegistered(name)) return;
            server.registerMBean(new StandardMBean(new Monitor(), MonitorMBean.class), name);
            registeredName = name;
        } catch (Exception failure) {
            log.warn("SMTP monitor registration failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** 仅暴露有界执行器计数，不提供收件人、任务内容或重发操作。 */
    public interface MonitorMBean {
        int getActive();

        int getQueued();

        long getCompleted();

        long getRejected();
    }

    private final class Monitor implements MonitorMBean {
        public int getActive() {
            return executor.getActiveCount();
        }

        public int getQueued() {
            return executor.getQueue().size();
        }

        public long getCompleted() {
            return executor.getCompletedTaskCount();
        }

        public long getRejected() {
            return rejected.get();
        }
    }

    @Override
    public void close() {
        scheduler.dispose();
        if (registeredName == null) return;
        try {
            ManagementFactory.getPlatformMBeanServer().unregisterMBean(registeredName);
        } catch (Exception failure) {
            log.warn("SMTP monitor removal failed: {}", failure.getClass().getSimpleName());
        } finally {
            registeredName = null;
        }
    }
}
