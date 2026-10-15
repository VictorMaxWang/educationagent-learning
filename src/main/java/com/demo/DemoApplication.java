package com.demo;

import com.demo.bot.BotContext;
import com.demo.bot.BotManager;
import com.demo.service.CmdService;
import com.demo.config.AiChatProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Scanner;

@SpringBootApplication(scanBasePackages = "com.demo")
@MapperScan({"com.demo.mapper", "com.demo.session.mapper", "com.demo.pet", "com.demo.edu.mapper"})
@EnableScheduling
@EnableAsync
public class DemoApplication {

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        SpringApplication.run(DemoApplication.class, args);
    }


    @Bean//机器人总管，直接拿来用
    public CommandLineRunner run(BotManager botManager, CmdService cmdService, AiChatProperties aiChat) {
        return args -> {
            // BotManager 已在 @PostConstruct 中按 bot.auto-start-count 自动启动 Bot

            // 测试环境（IntelliJ JUnit / Maven surefire）没有交互式控制台，
            // 若进入下方 sc.nextLine() 阻塞循环会让测试永远挂起，故直接跳过。
            if (isTestRunner()) {
                System.out.println("（检测到测试环境，跳过控制台交互循环）");
                return;
            }

            System.out.println();
            System.out.println("==== 多 Bot 实例管理系统 ====");
            System.out.println("命令列表：");
            System.out.println("  bot start N     启动 N 个 Bot 实例（如：bot start 2）");
            System.out.println("  bot stop NAME   停止指定 Bot（如：bot stop bot1）");
            System.out.println("  bot list        列出所有 Bot 状态");
            System.out.println("  status          查看运行状态");
            System.out.println("  help            查看帮助");
            System.out.println("  exit            退出程序");
            System.out.println("  其他输入          发送给控制台 Agent 处理");
            System.out.println();

            Scanner sc = new Scanner(System.in);
            while (true) {
                System.out.print(">>> ");
                // 后台进程可能没有 stdin；EOF 只结束控制台，不能让 Web 服务启动失败。
                if (!sc.hasNextLine()) break;
                String input = sc.nextLine().trim();

                if ("exit".equalsIgnoreCase(input)) {
                    System.out.println("程序退出");
                    botManager.shutdown();
                    break;
                }

                if (input.isEmpty()) continue;

                // Bot 管理命令
                //查看所有bot的状态
                if ("bot list".equalsIgnoreCase(input)) {
                    System.out.println(botManager.listBotsStatus());
                } else if (input.toLowerCase().startsWith("bot start ")) {
                    try {
                        int n = Integer.parseInt(input.substring(10).trim());
                        if (n <= 0 || n > 10) {//最多开10个账号
                            System.out.println("数量需在 1-10 之间");
                        } else {
                            botManager.startBots(n);//开始循环创建
                            System.out.println("已启动 " + n + " 个 Bot 实例（上方已打印二维码链接）");
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("用法: bot start <数量>，如：bot start 3");
                    }
                    //下线单个微信账号
                } else if (input.toLowerCase().startsWith("bot stop ")) {
                    String name = input.substring(9).trim();
                    if (botManager.stopBot(name)) {
                        System.out.println("已停止 " + name);
                    } else {
                        System.out.println("未找到 Bot: " + name);
                    }
                    //统计当前登录账号的总数
                } else if ("status".equalsIgnoreCase(input)) {
                    int online = botManager.getInstanceCount();
                    System.out.println("运行状态：正常 ✅");
                    System.out.println("在线 Bot 数：" + online);
                    System.out.println("文本模型：" + aiChat.getModel());
                } else if ("help".equalsIgnoreCase(input)) {
                    System.out.println("""
                        ============ 使用帮助 ============
                        bot start N     启动 N 个 Bot 实例
                        bot stop NAME   停止指定 Bot
                        bot list        列出所有 Bot 状态
                        status          查看运行状态
                        help            查看帮助
                        exit            退出程序
                        其他输入          发送给控制台 Agent 处理
                        =================================""");
                } else {
                    // 控制台 Agent 交互
                    BotContext.set(null, "console");
                    try {
                        String res = cmdService.dealMsg(input, "console");
                        System.out.println(res);
                    } finally {
                        BotContext.clear();
                    }
                }
            }
            sc.close();
        };
    }

    /**
     * 判断是否运行在测试环境（IntelliJ JUnit / Maven surefire）。
     * 此类场景 stdin 不可交互，跳过控制台循环避免测试挂起。
     */
    private static boolean isTestRunner() {
        String cmd = System.getProperty("sun.java.command", "");
        return cmd.contains("JUnitStarter") || cmd.contains("surefire");
    }
}
