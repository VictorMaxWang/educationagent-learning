package com.demo.testsupport;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;

/** 不执行周期任务；有自建调度器的业务 Bean 已由组合注解单独替换。 */
@TestConfiguration(proxyBeanMethods = false)
public class IsolatedTestConfiguration {
    @Bean
    TaskScheduler taskScheduler() {
        // 保留接口的默认 Clock 实现，但所有实际 schedule 方法均为空操作。
        return Mockito.mock(TaskScheduler.class, Mockito.CALLS_REAL_METHODS);
    }
}
