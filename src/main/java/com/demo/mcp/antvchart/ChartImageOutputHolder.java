package com.demo.mcp.antvchart;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 图表图片暂存 Holder —— 桥接「antv-chart MCP 返回的 base64 图片」与「发送层发图」。
 * <p>
 * 与 {@code FileConvertOutputHolder} 模式一致：MCP 图表工具调用成功后由
 * {@link AntvChartMcpSource} 解码写入，发送层（微信 client.sendImage / 桌面 {image} 事件）取出后清空。
 */
@Slf4j
@Component
public class ChartImageOutputHolder {

    private volatile byte[] bytes;

    /** 存入图表图片字节 */
    public synchronized void set(byte[] bytes) {
        this.bytes = bytes;
        log.info("📊 ChartImageOutputHolder 已缓存图表图片: {}KB", bytes.length / 1024);
    }

    /** 获取图表图片字节（无图返回 null） */
    public byte[] getBytes() {
        return bytes;
    }

    /** 是否有待发送图表 */
    public boolean has() {
        return bytes != null;
    }

    /** 发送完成后清空 */
    public synchronized void clear() {
        this.bytes = null;
    }
}
