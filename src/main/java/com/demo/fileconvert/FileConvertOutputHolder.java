package com.demo.fileconvert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 转换产物临时持有器 —— 桥接「@Tool / 预检执行转换」与「WechatMsgListener 发送文件」。
 * <p>
 * 与 {@link com.demo.service.tools.holder.UserImageHolder} 模式一致：
 * 转换成功后由工具写入，监听器在回复后取出用 {@code client.sendFile()} 发送并清空。
 */
@Slf4j
@Component
public class FileConvertOutputHolder {

    private volatile byte[] bytes;
    private volatile String fileName;
    /** 产物是否已落盘保存到本地（生成文件=true，转换产物=false）；桌面端据此决定是否触发下载 */
    private volatile boolean saved;

    /** 存入转换产物（未落盘，桌面端需触发下载） */
    public synchronized void set(byte[] bytes, String fileName) {
        set(bytes, fileName, false);
    }

    /** 存入产物 */
    public synchronized void set(byte[] bytes, String fileName, boolean saved) {
        this.bytes = bytes;
        this.fileName = fileName;
        this.saved = saved;
        log.info("📤 FileConvertOutputHolder 已缓存产物: {} ({}KB){}",
                fileName, bytes.length / 1024, saved ? " [已落盘]" : "");
    }

    /** 获取转换产物字节（无产物返回 null） */
    public byte[] getBytes() {
        return bytes;
    }

    /** 获取产物文件名 */
    public String getFileName() {
        return fileName;
    }

    /** 产物是否已保存到本地磁盘（桌面端不再重复下载） */
    public boolean isSaved() {
        return saved;
    }

    /** 是否有待发送产物 */
    public boolean has() {
        return bytes != null;
    }

    /** 发送完成后清空 */
    public synchronized void clear() {
        this.bytes = null;
        this.fileName = null;
        this.saved = false;
    }
}
