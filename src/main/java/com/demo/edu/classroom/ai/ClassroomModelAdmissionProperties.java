package com.demo.edu.classroom.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 课堂模型公共资源额度；不改变共享演示身份，也不把配额变成模型网络重试。 */
@Component
@ConfigurationProperties(prefix="classroom.model-admission")
public class ClassroomModelAdmissionProperties {
    private int maxConcurrent=2;
    private int maxWaiting=8;
    private Duration maxWait=Duration.ofSeconds(5);
    public int getMaxConcurrent(){return maxConcurrent;}
    public void setMaxConcurrent(int value){maxConcurrent=value;}
    public int getMaxWaiting(){return maxWaiting;}
    public void setMaxWaiting(int value){maxWaiting=value;}
    public Duration getMaxWait(){return maxWait;}
    public void setMaxWait(Duration value){maxWait=value;}
}
