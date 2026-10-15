package com.demo.service.tools.browser;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 持久化浏览器管理器。
 * <p>
 * 默认每次工具调用创建一个新浏览器，执行完立刻销毁——搜索完窗口一闪而关。
 * 此管理器在第一次工具调用时创建浏览器，之后复用同一窗口，只有应用关闭时才退出。
 * <p>
 * 注意：消息是串行处理的，不存在多线程争用问题。
 */
@Slf4j
@Component
public class PersistentBrowserManager {

    private WebDriver driver;

    /**
     * 获取持久化浏览器实例。第一次调用时创建，之后复用。
     */
    public synchronized WebDriver getDriver() {
        if (driver == null) {
            driver = createPersistentDriver();
        }
        return driver;
    }

    /**
     * 检查浏览器是否还活着（页面未崩溃、未被人手关闭）
     */
    public synchronized boolean isAlive() {
        if (driver == null) return false;
        try {
            driver.getTitle();
            return true;
        } catch (Exception e) {
            log.warn("持久浏览器已失效，下次调用将重建: {}", e.getMessage());
            try { driver.quit(); } catch (Exception ignored) {}
            driver = null;
            return false;
        }
    }

    /**
     * 创建持久化浏览器（非无头，窗口保留）
     */
    private WebDriver createPersistentDriver() {
        System.setProperty("webdriver.edge.driver", "D:\\driver\\edgedriver_win64\\msedgedriver.exe");

        EdgeOptions options = new EdgeOptions();
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--no-sandbox");
        options.addArguments("--window-size=1280,900");
        options.addArguments("--lang=zh-CN");
        options.addArguments("--remote-allow-origins=*");
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        options.setExperimentalOption("prefs", prefs);

        EdgeDriver d = new EdgeDriver(options);
        d.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        d.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));

        log.info("🌐 持久化浏览器已创建");

        // 打开空白标签防止空窗口
        d.get("about:blank");
        return d;
    }

    /** 应用关闭时退出浏览器 */
    @PreDestroy
    public void shutdown() {
        if (driver != null) {
            try {
                driver.quit();
                log.info("🌐 持久化浏览器已关闭");
            } catch (Exception e) {
                log.warn("关闭持久浏览器异常: {}", e.getMessage());
            }
            driver = null;
        }
    }
}
