package com.demo.mcp;

import com.demo.agent.AgentExecutor;
import com.demo.mcp.lazy.McpLazyActivator;
import com.demo.mcp.lazy.McpLazyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具桥接器 — 启动后登记全部 McpSource 引用并按策略加载。
 * <p>
 * 双触发机制：CommandLineRunner + ApplicationReadyEvent，保证至少触发一次。
 * <p>
 * 懒加载模式（{@code mcp.lazy.enabled=true}）：
 * 启动只「登记引用」（零网络，不连接、不发现工具），按 {@code mcp.lazy.preload} 白名单预加载，
 * 其余源由前端 {@code /api/mcp/*}、消息关键词、首次调用兜底按需激活。
 * 兼容模式（enabled=false）：等价于改造前行为，启动全量激活。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpToolBridge implements CommandLineRunner {

    private volatile boolean registered = false;

    private final McpClientManager manager;
    private final ApplicationContext ctx;
    private final McpLazyProperties lazyProps;
    private final McpLazyActivator activator;

    public McpToolBridge(McpClientManager manager, ApplicationContext ctx,
                         McpLazyProperties lazyProps, McpLazyActivator activator) {
        this.manager = manager;
        this.ctx = ctx;
        this.lazyProps = lazyProps;
        this.activator = activator;
    }

    @Override
    public void run(String... args) {
        doRegister();
    }

    /** 备用触发：ApplicationReadyEvent 也触发一次（双重保险） */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        doRegister();
    }

    private void doRegister() {
        if (registered) return;
        registered = true;

        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("[McpToolBridge] ===== 开始登记 MCP 工具 =====");
        log.info("🔗 [McpToolBridge] 开始登记 MCP 源引用...");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        Map<String, McpSource> sources = ctx.getBeansOfType(McpSource.class);
        System.out.println("[McpToolBridge] 发现 " + sources.size() + " 个 McpSource: " + sources.keySet());
        log.info("🔍 [McpToolBridge] 发现 {} 个 McpSource 实现: {}", sources.size(), sources.keySet());

        // 1. 全部登记为「引用」——零网络，不连接、不发现工具
        for (McpSource source : sources.values()) {
            manager.register(source);
        }

        // 2. 注入 AgentExecutor，供激活时动态添加/卸载 MCP 工具
        manager.setAgentExecutor(ctx.getBean(AgentExecutor.class));

        // 3. 按加载策略执行
        if (lazyProps.isEnabled()) {
            List<String> preload = lazyProps.getPreload();
            if (preload != null && !preload.isEmpty()) {
                for (String name : preload) {
                    activator.preloadAsync(name.trim());
                }
                System.out.println("[McpToolBridge] 🔌 懒加载模式：预加载白名单 " + preload);
            } else {
                System.out.println("[McpToolBridge] 🔌 懒加载模式：全部源待触发（前端 / 关键词 / 首次调用）");
            }
            log.info("✅ [McpToolBridge] 懒加载模式启动：{} 个源已登记（未连接），预加载={}",
                    sources.size(), preload);
        } else {
            // 兼容模式：等价于改造前行为（全量激活 + 注入）
            int ok = 0;
            for (String name : sources.keySet()) {
                if (manager.activate(name)) ok++;
            }
            System.out.println("[McpToolBridge] 🔧 兼容模式：已全量激活 " + ok + "/" + sources.size() + " 个源");
            log.info("✅ [McpToolBridge] 兼容模式：{} 个源全量激活", ok);
        }

        System.out.println("[McpToolBridge] ===== 完成: " + sources.size() + " 个源已登记 =====\n");
    }
}
