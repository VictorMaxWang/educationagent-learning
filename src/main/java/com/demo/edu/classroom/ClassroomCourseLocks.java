package com.demo.edu.classroom;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.Supplier;

/** 有界课程互斥；发布状态与课程提交共用同一锁序，未知课程访问不会无限创建锁对象。 */
final class ClassroomCourseLocks {
    private static final Object[] LOCKS = new Object[256];
    static { Arrays.setAll(LOCKS, ignored -> new Object()); }
    private ClassroomCourseLocks() {}
    private static int slot(String id) { return (id == null ? 0 : id.hashCode() & Integer.MAX_VALUE) % LOCKS.length; }
    static Object lock(String id) { return LOCKS[slot(id)]; }
    static <T> T withCourses(Collection<String> ids, Supplier<T> action) {
        int[] slots = ids.stream().mapToInt(ClassroomCourseLocks::slot).distinct().sorted().toArray();
        return acquire(slots, 0, action);
    }
    private static <T> T acquire(int[] slots, int index, Supplier<T> action) {
        if (index == slots.length) return action.get();
        synchronized (LOCKS[slots[index]]) { return acquire(slots, index + 1, action); }
    }
}
