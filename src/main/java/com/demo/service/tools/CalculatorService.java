package com.demo.service.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * 计算器后端服务
 * 使用 Spring SpEL 表达式引擎实现精确算术运算
 * 支持加减乘除、括号、幂运算、百分比、取模
 * 零外部依赖（Spring Boot 已内置 SpEL）
 */
@Slf4j
@Service
public class CalculatorService {

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 计算数学表达式的值
     * 预处理常见的数学表达习惯（如 ^ → pow, 百分比转换等）
     *
     * @param expression 用户输入的数学表达式，如 "25*48+137"、"2^10"、"sqrt(144)"
     * @return 格式化计算结果
     */
    public String calculate(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return "错误：表达式不能为空";
        }

        String processed = expression.trim();

        try {
            // 预处理：替换常见数学符号
            // 1. 替换 ^ 为 Math.pow：2^10 → T(java.lang.Math).pow(2,10)
            processed = replacePowerOperator(processed);
            // 2. 支持 sqrt → Math.sqrt：sqrt(144) → T(java.lang.Math).sqrt(144)
            processed = processed.replaceAll("(?i)sqrt", "T(java.lang.Math).sqrt");
            // 3. 去空格
            processed = processed.replaceAll("\\s+", "");

            log.info("🧮 计算表达式：{}", processed);

            // 使用 SpEL 计算
            Object result = parser.parseExpression(processed).getValue();

            // 格式化输出
            return formatResult(result, expression.trim());

        } catch (SpelEvaluationException e) {
            log.warn("🧮 表达式计算失败：{}", e.getMessage());
            return "算式格式有误，请检查后重试。支持的运算：+ - * / ^ ( ) sqrt() %";
        } catch (Exception e) {
            log.warn("🧮 表达式解析异常：{}", e.getMessage());
            return "无法计算该表达式，请检查输入是否正确。";
        }
    }

    /**
     * 将 x^y 替换为 SpEL 的 pow(x, y) 函数
     * 例如：2^10 → pow(2,10)，2^(3+1) → pow(2,(3+1))
     */
    private String replacePowerOperator(String expr) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '^') {
                // 向左找 base（数字或右括号）
                StringBuilder base = new StringBuilder();
                int j = sb.length() - 1;
                // 跳过空格
                while (j >= 0 && sb.charAt(j) == ' ') j--;
                if (j >= 0 && sb.charAt(j) == ')') {
                    // 匹配对应的左括号
                    int depth = 1;
                    int k = j - 1;
                    while (k >= 0 && depth > 0) {
                        if (sb.charAt(k) == ')') depth++;
                        else if (sb.charAt(k) == '(') depth--;
                        k--;
                    }
                    base.append(sb.substring(k + 1, j + 1));
                    sb.delete(k + 1, sb.length());
                } else {
                    // 提取数字
                    while (j >= 0 && (Character.isDigit(sb.charAt(j)) || sb.charAt(j) == '.')) {
                        base.insert(0, sb.charAt(j));
                        j--;
                    }
                    sb.delete(j + 1, sb.length());
                }

                // 向右找 exponent
                StringBuilder exp = new StringBuilder();
                int k = i + 1;
                if (k < expr.length() && expr.charAt(k) == '(') {
                    // 匹配对应右括号
                    int depth = 1;
                    exp.append('(');
                    k++;
                    while (k < expr.length() && depth > 0) {
                        if (expr.charAt(k) == '(') depth++;
                        else if (expr.charAt(k) == ')') depth--;
                        exp.append(expr.charAt(k));
                        k++;
                    }
                    sb.append("T(java.lang.Math).pow(").append(base).append(",").append(exp).append(")");
                } else {
                    while (k < expr.length() && (Character.isDigit(expr.charAt(k)) || expr.charAt(k) == '.')) {
                        exp.append(expr.charAt(k));
                        k++;
                    }
                    sb.append("T(java.lang.Math).pow(").append(base).append(",").append(exp).append(")");
                }
                i = k;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 格式化计算结果
     * 整数不显示小数点，小数保留最多 4 位有效数字
     */
    private String formatResult(Object result, String originalExpr) {
        if (result == null) {
            return "计算无结果";
        }

        String formatted;
        if (result instanceof Double || result instanceof Float) {
            double val = ((Number) result).doubleValue();
            if (val == Math.floor(val) && !Double.isInfinite(val)) {
                // 整数值 → 显示整数
                formatted = String.valueOf((long) val);
            } else {
                // 小数 → 保留 4 位
                DecimalFormat df = new DecimalFormat("#.####");
                df.setRoundingMode(RoundingMode.HALF_UP);
                formatted = df.format(val);
            }
        } else if (result instanceof Number) {
            formatted = result.toString();
        } else {
            formatted = result.toString();
        }

        return originalExpr + " = " + formatted;
    }
}
