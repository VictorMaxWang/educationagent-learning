package com.demo.service.tools.browser;

import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 浏览器自动化核心引擎。
 * <p>
 * 封装 WebDriver 生命周期管理、真人化输入/点击行为、元素查找容错，
 * 以及通用的表单填写与注册校验能力。
 * {@link BrowserSearchService} / {@link BrowserFormService} / {@link BrowserRegisterService}
 * 基于此类提供的底层能力实现各自业务。
 */
@Slf4j
@Component
public class BrowserAutomationCore {

    @Value("${browser.headless:true}")
    private boolean headless;

    @Value("${browser.page-load-timeout:30}")
    private int pageLoadTimeout;

    @Value("${browser.element-wait-timeout:10}")
    private int elementWaitTimeout;

    // ==================== 真人化时间参数 ====================

    @Value("${browser.typing.delay-min-ms:80}")
    private int typingDelayMin;

    @Value("${browser.typing.delay-max-ms:200}")
    private int typingDelayMax;

    @Value("${browser.pause.after-input-min-ms:300}")
    private int afterInputPauseMin;

    @Value("${browser.pause.after-input-max-ms:800}")
    private int afterInputPauseMax;

    @Value("${browser.pause.between-fields-min-ms:200}")
    private int betweenFieldsPauseMin;

    @Value("${browser.pause.between-fields-max-ms:500}")
    private int betweenFieldsPauseMax;

    @Value("${browser.pause.before-submit-min-ms:500}")
    private int beforeSubmitPauseMin;

    @Value("${browser.pause.before-submit-max-ms:1000}")
    private int beforeSubmitPauseMax;

    /** 操作日志缓冲区（sessionId → logs） */
    private final ConcurrentHashMap<String, List<String>> operationLogs = new ConcurrentHashMap<>();

    // ==================== 通用填表流程（供 Service 调用） ====================

    /**
     * 执行标准填表流程：打开页面 → 依次填写字段 → 回调后处理。
     * <p>
     * 将 Form 和 Register 的重复编排逻辑收敛到此方法，两个 Service 只需传不同回调。
     *
     * @param url         目标页面
     * @param fieldsSpec  字段规格（多行 field|selector|value|type）
     * @param label       业务标签（用于日志，如 "表单填写" / "注册"）
     * @param retryCount  当前重试次数（首次传 1，重试递归依次递增）
     * @param afterFill   填完后的回调，返回 result 对象（含截图/提交/校验结果）
     * @param <T>         最终返回类型
     * @return afterFill 回调的返回值
     */
    public <T> T executeFillFlow(String url, String fieldsSpec, String label, int retryCount,
                                  FillCallback<T> afterFill) {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        List<String> logs = new ArrayList<>();
        Map<String, String> fieldResults = new LinkedHashMap<>();
        WebDriver driver = null;

        logs.add(logTime() + " [开始] 模拟" + label + " session=" + sessionId);
        logs.add(logTime() + " 目标URL: " + url);

        try {
            List<FieldSpec> fields = parseFieldSpecs(fieldsSpec);
            if (fields.isEmpty()) {
                return afterFill.onEmptyFields(label, logs);
            }
            logs.add(logTime() + " 解析到 " + fields.size() + " 个字段");

            driver = createDriver();
            driver.get(url);
            waitForPageReady(driver);
            logs.add(logTime() + " 页面加载完成");

            // 依次填写字段
            for (int i = 0; i < fields.size(); i++) {
                FieldSpec field = fields.get(i);
                if (i > 0) {
                    randomPause(getBetweenFieldsPauseMin(), getBetweenFieldsPauseMax(),
                            "切换到[" + field.name() + "]前的停顿", logs);
                }
                fieldResults.put(field.name(), fillSingleField(driver, field, logs));
            }
            logs.add(logTime() + " 所有字段填写完成");

            return afterFill.onFieldsFilled(driver, fields, fieldResults, logs);

        } catch (Exception e) {
            log.error(label + "异常", e);
            logs.add(logTime() + " [异常] " + e.getMessage());
            return afterFill.onError(label, fieldsSpec, logs, retryCount);
        } finally {
            safeQuit(driver, logs);
        }
    }

    /**
     * 填表完成后回调接口
     */
    public interface FillCallback<T> {
        /** 字段规格解析为空 */
        T onEmptyFields(String label, List<String> logs);

        /** 字段全部填写完成，返回最终结果 */
        T onFieldsFilled(WebDriver driver, List<FieldSpec> fields,
                         Map<String, String> fieldResults, List<String> logs);

        /** 异常时触发重试 */
        T onError(String label, String fieldsSpec, List<String> logs, int retryCount);
    }

    // ==================== Getter（供子服务调用） ====================

    public int getAfterInputPauseMin() { return afterInputPauseMin; }
    public int getAfterInputPauseMax() { return afterInputPauseMax; }
    public int getBeforeSubmitPauseMin() { return beforeSubmitPauseMin; }
    public int getBeforeSubmitPauseMax() { return beforeSubmitPauseMax; }
    public int getBetweenFieldsPauseMin() { return betweenFieldsPauseMin; }
    public int getBetweenFieldsPauseMax() { return betweenFieldsPauseMax; }

    // ==================== WebDriver 管理 ====================

    /**
     * 创建并配置 EdgeDriver 实例。
     * <p>
     * Edge（Chromium 内核）的 CDP 兼容性、无头模式、反检测参数与 Chrome 一致，
     * 且微软国内 CDN 连通性远优于 Google，驱动下载更稳定。
     * 后续接入：自动读取本地 Edge 版本 → 微软官方源自动下载 msedgedriver。
     */
    public WebDriver createDriver() {
        System.setProperty("webdriver.edge.driver", "D:\\driver\\edgedriver_win64\\msedgedriver.exe");
        EdgeOptions options = new EdgeOptions();

        if (headless) {
            options.addArguments("--headless=new");
            options.addArguments("--disable-gpu");
        }

        // 反检测参数（Edge 基于 Chromium，参数通用）
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--no-sandbox");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--lang=zh-CN");
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        options.setExperimentalOption("prefs", prefs);

        EdgeDriver driver = new EdgeDriver(options);

        try {
            ((JavascriptExecutor) driver).executeScript(
                    "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
        } catch (Exception ignored) {}

        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(pageLoadTimeout));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(elementWaitTimeout));

        return driver;
    }

    public void safeQuit(WebDriver driver, List<String> logs) {
        if (driver != null) {
            try {
                driver.quit();
                logs.add(logTime() + " [关闭] 浏览器实例已关闭");
            } catch (Exception e) {
                log.warn("关闭 WebDriver 异常: {}", e.getMessage());
            }
        }
    }

    // ==================== 真人化行为模拟 ====================

    /**
     * 模拟真人点击：先移动元素到可见区域，再点击
     */
    public void humanClick(WebDriver driver, WebElement element, List<String> logs) {
        try {
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", element);
            Thread.sleep(randomBetween(50, 150));
            element.click();
            logs.add(logTime() + " [点击] " + describeElement(element));
        } catch (Exception e) {
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
                logs.add(logTime() + " [JS点击] " + describeElement(element));
            } catch (Exception ex) {
                logs.add(logTime() + " [点击失败] " + e.getMessage());
            }
        }
    }

    /**
     * 逐字符真人化输入，每个字符间隔 80–200ms
     */
    public void typeHuman(String text, WebElement element, List<String> logs) {
        if (text == null || text.isEmpty()) return;
        logs.add(logTime() + " [输入] 开始逐字符输入（" + text.length() + " 字符）: \"" + truncate(text, 30) + "\"");
        for (int i = 0; i < text.length(); i++) {
            element.sendKeys(String.valueOf(text.charAt(i)));
            sleep(randomBetween(typingDelayMin, typingDelayMax));
        }
        logs.add(logTime() + " [输入] 输入完成");
    }

    /**
     * 模拟人工清空输入框：Ctrl+A → Backspace
     */
    public void clearFieldHuman(WebElement element, List<String> logs) {
        try {
            String current = element.getAttribute("value");
            if (current != null && !current.isEmpty()) {
                element.sendKeys(Keys.chord(Keys.CONTROL, "a"));
                sleep(randomBetween(50, 100));
                element.sendKeys(Keys.BACK_SPACE);
                logs.add(logTime() + " [清空] 原内容长度 " + current.length());
            }
        } catch (Exception e) {
            try { element.clear(); } catch (Exception ignored) {}
        }
    }

    /**
     * 随机停顿（模拟自然停顿）
     */
    public void randomPause(int minMs, int maxMs, String reason, List<String> logs) {
        int pauseMs = randomBetween(minMs, maxMs);
        logs.add(logTime() + " [停顿] " + reason + ": " + pauseMs + "ms");
        sleep(pauseMs);
    }

    // ==================== 元素查找与容错 ====================

    /**
     * 带重试的元素查找。等待 3s 后重试，最多 3 次
     */
    public WebElement findElementWithRetry(WebDriver driver, String selector, List<String> logs) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (attempt > 0) {
                    logs.add(logTime() + " [重试] 第 " + attempt + " 次重试: " + selector);
                    sleep(3000);
                }
                By by = parseSelector(selector);
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(elementWaitTimeout));
                WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(by));
                wait.until(ExpectedConditions.visibilityOfElementLocated(by));
                return element;
            } catch (Exception e) {
                if (attempt == 2) {
                    logs.add(logTime() + " [失败] 元素查找重试耗尽: " + selector + " (" + e.getMessage() + ")");
                }
            }
        }
        return null;
    }

    /**
     * 等待页面加载完成
     */
    public void waitForPageReady(WebDriver driver) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(pageLoadTimeout));
            wait.until(d -> ((JavascriptExecutor) d).executeScript("return document.readyState").equals("complete"));
        } catch (Exception e) {
            log.warn("等待页面加载超时: {}", e.getMessage());
        }
    }

    // ==================== 表单字段操作 ====================

    /**
     * 填写单个表单字段
     */
    public String fillSingleField(WebDriver driver, FieldSpec field, List<String> logs) {
        try {
            WebElement element = findElementWithRetry(driver, field.selector, logs);
            if (element == null) {
                logs.add(logTime() + " [失败] 字段[" + field.name + "] 元素未找到: " + field.selector);
                return "元素未找到";
            }
            if (isReadonlyOrDisabled(element)) {
                logs.add(logTime() + " [跳过] 字段[" + field.name + "] 为只读/禁用状态");
                return "只读/禁用，已跳过";
            }
            if ("checkbox".equalsIgnoreCase(field.type)) {
                handleCheckbox(driver, element, field, logs);
                return "成功";
            }
            humanClick(driver, element, logs);
            clearFieldHuman(element, logs);
            if (field.value != null) {
                typeHuman(field.value, element, logs);
            }
            logs.add(logTime() + " [完成] 字段[" + field.name + "] 填写完毕");
            return "成功";
        } catch (Exception e) {
            logs.add(logTime() + " [异常] 字段[" + field.name + "]: " + e.getMessage());
            return "异常: " + e.getMessage();
        }
    }

    private void handleCheckbox(WebDriver driver, WebElement element, FieldSpec field, List<String> logs) {
        if ("true".equalsIgnoreCase(field.value) || "yes".equalsIgnoreCase(field.value) || "1".equals(field.value)) {
            if (!element.isSelected()) {
                humanClick(driver, element, logs);
                logs.add(logTime() + " [勾选] " + field.name);
            } else {
                logs.add(logTime() + " [已勾选] " + field.name + "，跳过");
            }
        } else {
            if (element.isSelected()) {
                humanClick(driver, element, logs);
                logs.add(logTime() + " [取消勾选] " + field.name);
            }
        }
    }

    // ==================== 注册表单校验 ====================

    /**
     * 提交前校验注册字段：手机号、邮箱、密码一致性、必填非空
     */
    public String validateRegistrationFields(List<FieldSpec> fields) {
        String password = null;
        String confirmPassword = null;

        for (FieldSpec f : fields) {
            if (f.value == null || f.value.isBlank()) {
                return "字段[" + f.name + "] 为必填项，值不能为空";
            }
            if ("phone".equalsIgnoreCase(f.type) || "tel".equalsIgnoreCase(f.type)
                    || "mobile".equalsIgnoreCase(f.type)) {
                String phone = f.value.replaceAll("\\s+|-", "");
                if (!phone.matches("^1[3-9]\\d{9}$")) {
                    return "手机号格式不正确: " + f.value;
                }
            }
            if ("email".equalsIgnoreCase(f.type)) {
                if (!f.value.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                    return "邮箱格式不正确: " + f.value;
                }
            }
            String nameLower = f.name.toLowerCase();
            if ("password".equalsIgnoreCase(f.type) || nameLower.contains("密码")
                    || nameLower.contains("password")) {
                if (nameLower.contains("确认") || nameLower.contains("confirm")
                        || nameLower.contains("再次")) {
                    confirmPassword = f.value;
                } else {
                    password = f.value;
                }
            }
        }
        if (password != null && confirmPassword != null && !password.equals(confirmPassword)) {
            return "密码与确认密码不一致";
        }
        return null;
    }

    // ==================== 提交结果检测 ====================

    /**
     * 监听提交结果：成功跳转 / 弹窗 / 字段报错 / 风控拦截
     */
    public String detectSubmitResult(WebDriver driver, List<String> logs) {
        try {
            sleep(2000);
            String currentUrl = driver.getCurrentUrl();
            String pageText = driver.getPageSource().toLowerCase();

            if (pageText.contains("验证码") || pageText.contains("captcha")
                    || pageText.contains("人机验证") || pageText.contains("请完成安全验证")) {
                logs.add(logTime() + " [风控] 检测到验证码/人机验证弹窗");
                return "captcha_blocked";
            }
            List<String> errorPatterns = List.of(
                    "请输入", "必填", "格式不正确", "格式错误", "不能为空",
                    "密码不一致", "两次输入不一致", "手机号", "邮箱格式",
                    "is required", "invalid", "error", "must be");
            for (String pattern : errorPatterns) {
                if (pageText.contains(pattern)) {
                    logs.add(logTime() + " [字段报错] 检测到: " + pattern);
                    return "validation_error";
                }
            }
            List<String> successPatterns = List.of(
                    "注册成功", "登录成功", "success", "welcome",
                    "恭喜", "账号创建成功", "验证邮件已发送");
            for (String pattern : successPatterns) {
                if (pageText.contains(pattern)) {
                    logs.add(logTime() + " [成功] 检测到成功标志: " + pattern);
                    return "success";
                }
            }
            if (!currentUrl.equals(driver.getCurrentUrl())) {
                logs.add(logTime() + " [跳转] 页面已跳转: " + driver.getCurrentUrl());
                return "success";
            }
            logs.add(logTime() + " [未确定] 无法明确判断提交结果");
            return "unknown";
        } catch (Exception e) {
            logs.add(logTime() + " [检测异常] " + e.getMessage());
            return "unknown";
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析选择器：// 开头 → XPath，其他 → CSS
     */
    public By parseSelector(String selector) {
        String trimmed = selector.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("(//")) {
            return By.xpath(trimmed);
        }
        return By.cssSelector(trimmed);
    }

    /**
     * 解析字段规格文本：fieldName|selector|value|type，多字段换行分隔
     */
    public List<FieldSpec> parseFieldSpecs(String spec) {
        List<FieldSpec> fields = new ArrayList<>();
        if (spec == null || spec.isBlank()) return fields;

        for (String line : spec.split("\\n")) {
            line = line.trim();
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) continue;

            String[] parts = line.split("\\|");
            if (parts.length < 3) {
                log.warn("字段规格格式不正确: {}", line);
                continue;
            }
            fields.add(new FieldSpec(
                    parts[0].trim(),
                    parts[1].trim(),
                    parts[2].trim(),
                    parts.length > 3 ? parts[3].trim() : "text"));
        }
        return fields;
    }

    public boolean isReadonlyOrDisabled(WebElement element) {
        try {
            String readonly = element.getAttribute("readonly");
            String disabled = element.getAttribute("disabled");
            return "true".equalsIgnoreCase(readonly) || "readonly".equalsIgnoreCase(readonly)
                    || "true".equalsIgnoreCase(disabled) || "disabled".equalsIgnoreCase(disabled)
                    || !element.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 工具方法 ====================

    public int randomBetween(int min, int max) {
        if (min >= max) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public void sleep(int ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String logTime() {
        return "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "]";
    }

    public String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String describeElement(WebElement element) {
        try {
            String tag = element.getTagName();
            String id = element.getAttribute("id");
            String name = element.getAttribute("name");
            String placeholder = element.getAttribute("placeholder");
            if (id != null && !id.isBlank()) return tag + "#" + id;
            if (name != null && !name.isBlank()) return tag + "[name=" + name + "]";
            if (placeholder != null && !placeholder.isBlank()) return tag + "[placeholder=" + truncate(placeholder, 20) + "]";
            return tag;
        } catch (Exception e) {
            return "?";
        }
    }

    // ==================== 内部类型 ====================

    public record FieldSpec(String name, String selector, String value, String type) {}
}
