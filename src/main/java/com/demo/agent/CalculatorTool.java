package com.demo.agent;

import com.demo.service.tools.CalculatorService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 精确计算器
 * Agent 会自动识别用户的计算需求并调用此工具
 * 支持四则运算、幂运算、平方根、括号等
 * 解决 LLM 在数学计算上容易出错的问题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalculatorTool {

    private final CalculatorService calculatorService;

    @Tool("执行精确数学计算。支持加减乘除 + - * /、幂运算 ^、平方根 sqrt()、括号 ()、取模 %%。"
            + "当用户要求计算任何数学表达式时，必须调用此工具，禁止 LLM 自行计算。"
            + "例如：25*48+137、2^10、sqrt(144)、(15+3)*2")
    public String calculate(
            @P("数学表达式，如：25*48+137、2^10=1024、sqrt(144)=12、(15+3)*2=36")
            String expression) {
        log.info("🧰 Agent 调用计算器工具，表达式：{}", expression);
        String result = calculatorService.calculate(expression);
        log.info("🧰 计算器工具返回：{}", result);
        return result;
    }
}
