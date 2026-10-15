package com.demo.service.tools.browser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 浏览器模拟表单填写服务。
 * 在指定网页上模拟真人逐字段输入，支持填后截图。
 * <p>
 * 填表共性流程（打开页面 → 字段循环）委托给 {@link BrowserAutomationCore#executeFillFlow}，
 * 本类仅负责「填完后截图」的差异化逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserFormService {

    private final BrowserAutomationCore core;

    public record FormResult(
            boolean success,
            String message,
            Map<String, String> fieldResults,
            List<String> logs,
            byte[] screenshotBytes
    ) {}

    public FormResult fillForm(String url, String fieldsSpec) {
        return fillForm(url, fieldsSpec, false);
    }

    public FormResult fillForm(String url, String fieldsSpec, boolean captureAfterFill) {
        return fillForm(url, fieldsSpec, captureAfterFill, 1);
    }

    private FormResult fillForm(String url, String fieldsSpec, boolean captureAfterFill, int retryCount) {
        return core.executeFillFlow(url, fieldsSpec, "表单填写", retryCount, new BrowserAutomationCore.FillCallback<FormResult>() {

            @Override
            public FormResult onEmptyFields(String label, List<String> logs) {
                return new FormResult(false, "未提供有效的表单字段信息", Map.of(), logs, null);
            }

            @Override
            public FormResult onFieldsFilled(WebDriver driver,
                                              List<BrowserAutomationCore.FieldSpec> fields,
                                              Map<String, String> fieldResults,
                                              List<String> logs) {
                byte[] screenshotBytes = captureScreenshotIfNeeded(driver, captureAfterFill, logs);
                return new FormResult(true, "表单填写完成，共处理 " + fields.size() + " 个字段",
                        fieldResults, logs, screenshotBytes);
            }

            @Override
            public FormResult onError(String label, String fieldsSpec,
                                      List<String> logs, int retryCount) {
                return retryFillForm(url, fieldsSpec, captureAfterFill, logs, retryCount);
            }
        });
    }

    private byte[] captureScreenshotIfNeeded(WebDriver driver, boolean captureAfterFill, List<String> logs) {
        if (!captureAfterFill) return null;
        try {
            core.sleep(2000);
            ((JavascriptExecutor) driver).executeScript(
                    "window.scrollTo(0, 0); document.body.scrollHeight;");
            core.sleep(800);

            EdgeDriver edgeDriver = (EdgeDriver) driver;
            Map<String, Object> cdpParam = new HashMap<>();
            cdpParam.put("captureBeyondViewport", true);
            Map<String, Object> cdpResult = edgeDriver.executeCdpCommand("Page.captureScreenshot", cdpParam);
            String base64Str = (String) cdpResult.get("data");
            if (base64Str != null && !base64Str.isEmpty()) {
                byte[] bytes = Base64.getDecoder().decode(base64Str);
                logs.add(core.logTime() + " [截图成功] 全页截图 " + bytes.length / 1024 + "KB");
                return bytes;
            }
            logs.add(core.logTime() + " [截图失败] CDP 返回空 data");
        } catch (Exception e) {
            logs.add(core.logTime() + " [截图异常] " + e.getMessage());
            log.warn("填表截图失败", e);
        }
        return null;
    }

    private FormResult retryFillForm(String url, String fieldsSpec, boolean captureAfterFill,
                                     List<String> logs, int retryCount) {
        if (retryCount >= 3) {
            logs.add(core.logTime() + " [终止] 重试 " + retryCount + " 次耗尽");
            return new FormResult(false, "表单填写失败，已重试 3 次", Map.of(), logs, null);
        }
        core.sleep(core.randomBetween(1000, 2000));
        logs.add(core.logTime() + " [重试 " + retryCount + "/3]");
        return fillForm(url, fieldsSpec, captureAfterFill, retryCount + 1);
    }
}
