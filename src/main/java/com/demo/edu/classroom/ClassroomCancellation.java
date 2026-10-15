package com.demo.edu.classroom;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 每个生成任务独有的取消句柄，只终止自己启动的 Node 进程。 */
public final class ClassroomCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Process> process = new AtomicReference<>();
    public void cancel() {
        cancelled.set(true);
        Process owned = process.get();
        if (owned != null) owned.destroyForcibly();
    }
    public void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("课堂生成已取消");
    }
    public boolean isCancelled() { return cancelled.get(); }
    void attach(Process owned) {
        process.set(owned);
        if (cancelled.get()) owned.destroyForcibly();
        check();
    }
    void detach(Process owned) { process.compareAndSet(owned, null); }
}
