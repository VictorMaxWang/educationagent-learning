package com.demo.skill;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 参数类型转换器。
 * <p>
 * Router 从文本正则提取的值全是字符串，
 * 按 {@link SkillParam#type()} 自动转为目标类型。
 * <p>
 * 支持类型：
 * <ul>
 *   <li>{@code String} → 原样返回</li>
 *   <li>{@code Integer / int} → {@code Integer.parseInt}</li>
 *   <li>{@code Long / long} → {@code Long.parseLong}</li>
 *   <li>{@code Double / double} → {@code Double.parseDouble}</li>
 *   <li>{@code Boolean / boolean} → {@code Boolean.parseBoolean}</li>
 *   <li>{@code LocalDate} → 自动识别 yyyy-MM-dd、yyyyMMdd、MM-dd</li>
 *   <li>{@code LocalDateTime} → yyyy-MM-dd HH:mm:ss</li>
 * </ul>
 */
@Slf4j
public final class ParamConverter {

    private ParamConverter() {}

    private static final Set<Class<?>> SUPPORTED = new HashSet<>(Arrays.asList(
            String.class, Integer.class, int.class, Long.class, long.class,
            Double.class, double.class, Boolean.class, boolean.class,
            LocalDate.class, LocalDateTime.class
    ));

    /**
     * 将原始字符串按目标类型转换。
     *
     * @param value      原始字符串值
     * @param targetType 目标类型
     * @return 转换后的对象
     * @throws SkillInvokeException 类型不支持或转换失败
     */
    public static Object convert(String value, Class<?> targetType) {
        if (value == null) return null;
        String trimmed = value.trim();

        try {
            if (targetType == String.class) return trimmed;
            if (targetType == Integer.class || targetType == int.class) return Integer.parseInt(trimmed);
            if (targetType == Long.class || targetType == long.class) return Long.parseLong(trimmed);
            if (targetType == Double.class || targetType == double.class) return Double.parseDouble(trimmed);
            if (targetType == Boolean.class || targetType == boolean.class) return Boolean.parseBoolean(trimmed);

            if (targetType == LocalDate.class) return parseLocalDate(trimmed);
            if (targetType == LocalDateTime.class) return LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            throw new SkillInvokeException("ParamConverter 不支持的类型: " + targetType.getName());
        } catch (SkillInvokeException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillInvokeException("参数转换失败: value='" + trimmed + "', targetType=" + targetType.getSimpleName(), e);
        }
    }

    /** 检查某一类型是否受支持 */
    public static boolean supports(Class<?> type) {
        return SUPPORTED.contains(type);
    }

    // ======================== 日期解析 ========================

    private static LocalDate parseLocalDate(String text) {
        // 按格式优先级尝试
        try { return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd")); } catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyyMMdd")); } catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(text, DateTimeFormatter.ofPattern("MM-dd")); } catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(text); } catch (DateTimeParseException ignored) {}
        throw new IllegalArgumentException("无法解析日期: " + text);
    }
}
