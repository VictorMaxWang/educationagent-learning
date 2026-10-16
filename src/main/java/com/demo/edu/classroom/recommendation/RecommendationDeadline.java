package com.demo.edu.classroom.recommendation;

import java.time.Duration;
import java.util.concurrent.CancellationException;

/** 单次推荐任务共用的单调时钟预算，阶段耗时不能各自重置为完整预算。 */
final class RecommendationDeadline {
    private final long deadline;
    RecommendationDeadline(Duration budget) {
        if(budget==null||budget.isZero()||budget.isNegative()||budget.compareTo(Duration.ofSeconds(90))>0)
            throw new IllegalArgumentException("recommendation budget must be positive and at most 90 seconds");
        deadline=System.nanoTime()+budget.toNanos();
    }
    Duration remaining(Duration cap) {
        if(Thread.currentThread().isInterrupted())throw new CancellationException("推荐已取消");
        long left=deadline-System.nanoTime();
        if(left<=0)throw new IllegalStateException("RESOURCE_PREPARATION_DEADLINE");
        return Duration.ofNanos(Math.min(left,cap.toNanos()));
    }
    void check(){remaining(Duration.ofSeconds(90));}
}
