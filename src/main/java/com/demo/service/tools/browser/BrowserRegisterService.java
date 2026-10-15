package com.demo.service.tools.browser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.edge.EdgeDriver;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 浏览器模拟注册服务。
 * 完整模拟用户注册流程：多字段填写 → 提交前校验 → 勾选协议 → 点击提交 → 监听结果。
 * <p>
 * 填表共性流程（打开页面 → 字段循环）委托给 {@link BrowserAutomationCore#executeFillFlow}，
 * 本类仅负责「提交前校验 + 勾选协议 + 提交 + 监听」的差异化逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserRegisterService {

    private final BrowserAutomationCore core;

    public record RegisterResult(
            boolean success,
            String message,
            String submitStatus,
            Map<String, String> fieldResults,
            List<String> logs,
            byte[] screenshotBytes
    ) {}

    public RegisterResult simulateRegister(String url, String fieldsSpec,
                                            String submitSelector, String agreementSelector) {
        return simulateRegister(url, fieldsSpec, submitSelector, agreementSelector, false);
    }

    public RegisterResult simulateRegister(String url, String fieldsSpec,
                                            String submitSelector, String agreementSelector,
                                            boolean captureAfterSubmit) {
        // 提交前校验（在开浏览器之前，省一次开销）
        List<BrowserAutomationCore.FieldSpec> fields = core.parseFieldSpecs(fieldsSpec);
        if (!fields.isEmpty()) {
            String validationError = core.validateRegistrationFields(fields);
            if (validationError != null) {
                List<String> logs = new ArrayList<>();
                logs.add(core.logTime() + " [校验失败] " + validationError);
                return new RegisterResult(false, validationError, "validation_error", Map.of(), logs, null);
            }
        }

        return simulateRegister(url, fieldsSpec, submitSelector, agreementSelector, captureAfterSubmit, 1);
    }

    private RegisterResult simulateRegister(String url, String fieldsSpec,
                                             String submitSelector, String agreementSelector,
                                             boolean captureAfterSubmit, int retryCount) {
        // 提交前校验（在开浏览器之前，省一次开销）
        List<BrowserAutomationCore.FieldSpec> fields = core.parseFieldSpecs(fieldsSpec);
        if (!fields.isEmpty()) {
            String validationError = core.validateRegistrationFields(fields);
            if (validationError != null) {
                List<String> logs = new ArrayList<>();
                logs.add(core.logTime() + " [校验失败] " + validationError);
                return new RegisterResult(false, validationError, "validation_error", Map.of(), logs, null);
            }
        }

        final boolean doCapture = captureAfterSubmit;

        return core.executeFillFlow(url, fieldsSpec, "注册", retryCount, new BrowserAutomationCore.FillCallback<RegisterResult>() {

            @Override
            public RegisterResult onEmptyFields(String label, List<String> logs) {
                return new RegisterResult(false, "未提供有效的注册字段", "unknown", Map.of(), logs, null);
            }

            @Override
            public RegisterResult onFieldsFilled(WebDriver driver,
                                                  List<BrowserAutomationCore.FieldSpec> filledFields,
                                                  Map<String, String> fieldResults,
                                                  List<String> logs) {
                // 勾选协议
                if (agreementSelector != null && !agreementSelector.isBlank()) {
                    logs.add(core.logTime() + " 处理协议勾选框...");
                    WebElement agreement = core.findElementWithRetry(driver, agreementSelector, logs);
                    if (agreement != null && !agreement.isSelected()) {
                        core.humanClick(driver, agreement, logs);
                        logs.add(core.logTime() + " 已勾选用户协议");
                    }
                }

                // 提交前停顿
                core.randomPause(core.getBeforeSubmitPauseMin(), core.getBeforeSubmitPauseMax(),
                        "提交前核对停顿", logs);

                // 点击提交
                WebElement submitBtn = core.findElementWithRetry(driver, submitSelector, logs);
                if (submitBtn == null) {
                    return new RegisterResult(false, "提交按钮未找到: " + submitSelector,
                            "unknown", fieldResults, logs, null);
                }
                core.humanClick(driver, submitBtn, logs);
                logs.add(core.logTime() + " 已点击提交按钮");

                // 监听结果
                String submitStatus = core.detectSubmitResult(driver, logs);

                // 提交后截图（展示结果页）
                byte[] screenshotBytes = null;
                if (doCapture) {
                    screenshotBytes = captureScreenshot(driver, logs);
                }

                return new RegisterResult("success".equals(submitStatus),
                        "success".equals(submitStatus) ? "注册提交成功" : "注册结果: " + submitStatus,
                        submitStatus, fieldResults, logs, screenshotBytes);
            }

            @Override
            public RegisterResult onError(String label, String spec,
                                          List<String> logs, int retryCount) {
                return retryRegister(url, fieldsSpec, submitSelector, agreementSelector, doCapture, logs, retryCount);
            }
        });
    }

    private byte[] captureScreenshot(WebDriver driver, List<String> logs) {
        try {
            core.sleep(1500);
            ((JavascriptExecutor) driver).executeScript(
                    "window.scrollTo(0, 0); document.body.scrollHeight;");
            core.sleep(500);

            EdgeDriver edgeDriver = (EdgeDriver) driver;
            Map<String, Object> cdpParam = new HashMap<>();
            cdpParam.put("captureBeyondViewport", true);
            Map<String, Object> cdpResult = edgeDriver.executeCdpCommand("Page.captureScreenshot", cdpParam);
            String base64Str = (String) cdpResult.get("data");
            if (base64Str != null && !base64Str.isEmpty()) {
                byte[] bytes = Base64.getDecoder().decode(base64Str);
                logs.add(core.logTime() + " [截图成功] 结果页截图 " + bytes.length / 1024 + "KB");
                return bytes;
            }
            logs.add(core.logTime() + " [截图失败] CDP 返回空 data");
        } catch (Exception e) {
            logs.add(core.logTime() + " [截图异常] " + e.getMessage());
            log.warn("注册截图失败", e);
        }
        return null;
    }

    private RegisterResult retryRegister(String url, String fieldsSpec, String submitSelector,
                                          String agreementSelector, boolean captureAfterSubmit,
                                          List<String> logs, int retryCount) {
        if (retryCount >= 3) {
            logs.add(core.logTime() + " [终止] 重试 " + retryCount + " 次耗尽");
            return new RegisterResult(false, "注册失败，已重试 3 次", "unknown", Map.of(), logs, null);
        }
        core.sleep(core.randomBetween(1000, 2000));
        logs.add(core.logTime() + " [重试 " + retryCount + "/3]");
        return simulateRegister(url, fieldsSpec, submitSelector, agreementSelector, captureAfterSubmit, retryCount + 1);
    }
}
