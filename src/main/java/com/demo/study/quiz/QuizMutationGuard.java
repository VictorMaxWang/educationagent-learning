package com.demo.study.quiz;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 当前单实例服务内，原题修订与首次作答共用试卷锁；不宣称提供跨JVM分布式锁。
 * 多卷按条带序号加锁以避免锁顺序反转；有外层Spring事务时一直持有到提交/回滚结束。
 */
public final class QuizMutationGuard {
    private static final ReentrantLock[] LOCKS = new ReentrantLock[256];
    static { Arrays.setAll(LOCKS, ignored -> new ReentrantLock()); }
    private QuizMutationGuard() {}

    public static <T> T withPaper(Long paperId, Supplier<T> action) {
        if (paperId == null || paperId <= 0) throw new IllegalArgumentException("试卷标识无效");
        return withPapers(List.of(paperId), action);
    }

    public static <T> T withPapers(Collection<Long> paperIds, Supplier<T> action) {
        if (paperIds == null || paperIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("试卷标识无效");
        List<ReentrantLock> acquired = paperIds.stream().map(id -> (id.hashCode() & Integer.MAX_VALUE) % LOCKS.length)
                .distinct().sorted().map(index -> LOCKS[index]).toList();
        acquired.forEach(ReentrantLock::lock);
        boolean deferred = false;
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { release(acquired); }
                });
                deferred = true;
            }
            return action.get();
        } finally {
            if (!deferred) release(acquired);
        }
    }

    private static void release(List<ReentrantLock> acquired) {
        for (int i = acquired.size() - 1; i >= 0; i--) acquired.get(i).unlock();
    }
}
