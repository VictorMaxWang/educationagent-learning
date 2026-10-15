package com.demo.service.tools.browser;

import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 浏览器模拟搜索服务。
 * 使用 {@link PersistentBrowserManager} 保持浏览器窗口常驻，
 * 搜索完成后窗口保留在桌面，不会一闪而关。
 */
@Slf4j
@Service
public class BrowserSearchService {

    private final BrowserAutomationCore core;
    private final PersistentBrowserManager browserManager;

    public BrowserSearchService(BrowserAutomationCore core, PersistentBrowserManager browserManager) {
        this.core = core;
        this.browserManager = browserManager;
    }

    public record SearchResult(boolean success, String message, List<String> logs) {}

    public SearchResult simulateSearch(String url, String keyword, String searchBoxSelector, String submitSelector) {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        List<String> logs = new ArrayList<>();
        WebDriver driver = null;

        logs.add(core.logTime() + " [开始] 模拟搜索 session=" + sessionId);
        logs.add(core.logTime() + " 目标URL: " + url);
        logs.add(core.logTime() + " 关键词: " + keyword);

        try {
            // 使用持久化浏览器（窗口保留在桌面，不会一闪而关）
            driver = browserManager.getDriver();

            // 新建标签页打开目标（不覆盖当前页面）
            if (!url.equals(driver.getCurrentUrl()) || "about:blank".equals(driver.getCurrentUrl())) {
                ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("window.open('about:blank','_blank');");
                java.util.ArrayList<String> tabs = new java.util.ArrayList<>(driver.getWindowHandles());
                driver.switchTo().window(tabs.get(tabs.size() - 1));
            }
            driver.get(url);
            core.waitForPageReady(driver);
            logs.add(core.logTime() + " 页面加载完成");

            WebElement searchBox = core.findElementWithRetry(driver, searchBoxSelector, logs);
            if (searchBox == null)
                return new SearchResult(false, "搜索框未找到: " + searchBoxSelector, logs);

            core.humanClick(driver, searchBox, logs);
            core.clearFieldHuman(searchBox, logs);
            core.typeHuman(keyword, searchBox, logs);
            core.randomPause(core.getAfterInputPauseMin(), core.getAfterInputPauseMax(), "输入完成思考停顿", logs);

            if (submitSelector != null && !submitSelector.isBlank()) {
                WebElement submitBtn = core.findElementWithRetry(driver, submitSelector, logs);
                if (submitBtn != null) {
                    core.humanClick(driver, submitBtn, logs);
                } else {
                    searchBox.sendKeys(Keys.ENTER);
                    logs.add(core.logTime() + " 搜索按钮未找到，改用回车");
                }
            } else {
                searchBox.sendKeys(Keys.ENTER);
                logs.add(core.logTime() + " 发送回车提交搜索");
            }

            core.waitForPageReady(driver);
            logs.add(core.logTime() + " 搜索完成");
            return new SearchResult(true, "✅ 已打开「" + url + "」并搜索「" + keyword + "」，浏览器窗口保留在桌面。", logs);

        } catch (Exception e) {
            log.error("搜索异常", e);
            logs.add(core.logTime() + " [异常] " + e.getMessage());
            // 如果浏览器挂了重建后再试
            if (!browserManager.isAlive()) {
                return retrySearch(url, keyword, searchBoxSelector, submitSelector, logs, 1);
            }
            return new SearchResult(false, "搜索失败: " + e.getMessage(), logs);
        }
        // 没有 finally safeQuit——浏览器窗口保留
    }

    private SearchResult retrySearch(String url, String keyword, String searchBoxSelector,
                                     String submitSelector, List<String> logs, int retryCount) {
        if (retryCount >= 3) {
            logs.add(core.logTime() + " [终止] 重试 " + retryCount + " 次耗尽");
            return new SearchResult(false, "搜索失败，已重试 3 次", logs);
        }
        core.sleep(core.randomBetween(1000, 2000));
        logs.add(core.logTime() + " [重试 " + retryCount + "/3]");
        try {
            WebDriver driver = browserManager.getDriver();
            driver.get(url);
            core.waitForPageReady(driver);

            WebElement searchBox = core.findElementWithRetry(driver, searchBoxSelector, logs);
            if (searchBox == null)
                return retrySearch(url, keyword, searchBoxSelector, submitSelector, logs, retryCount + 1);

            core.humanClick(driver, searchBox, logs);
            core.clearFieldHuman(searchBox, logs);
            core.typeHuman(keyword, searchBox, logs);
            core.randomPause(core.getAfterInputPauseMin(), core.getAfterInputPauseMax(), "停顿(重试)", logs);

            if (submitSelector != null && !submitSelector.isBlank()) {
                WebElement btn = core.findElementWithRetry(driver, submitSelector, logs);
                if (btn != null) core.humanClick(driver, btn, logs);
                else searchBox.sendKeys(Keys.ENTER);
            } else {
                searchBox.sendKeys(Keys.ENTER);
            }
            core.waitForPageReady(driver);
            logs.add(core.logTime() + " [重试成功]");
            return new SearchResult(true, "搜索完成（重试 " + retryCount + " 次后成功）", logs);
        } catch (Exception e) {
            return retrySearch(url, keyword, searchBoxSelector, submitSelector, logs, retryCount + 1);
        }
    }
}
