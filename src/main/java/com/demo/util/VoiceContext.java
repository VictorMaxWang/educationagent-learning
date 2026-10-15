package com.demo.util;

/**
 *   VoiceContext.setUserId(userId);   // 消息开始处理时存入用户ID
 *   String uid = VoiceContext.getUserId();  // 工具类内部获取使用
 *   VoiceContext.clear();             // 消息处理完毕清空上下文
 */
public class VoiceContext {

    private static final ThreadLocal<String> currentUserId = new ThreadLocal<>();

    public static void setUserId(String userId) {
        currentUserId.set(userId);
    }

    public static String getUserId() {
        return currentUserId.get();
    }

    public static void clear() {
        currentUserId.remove();
    }
}
