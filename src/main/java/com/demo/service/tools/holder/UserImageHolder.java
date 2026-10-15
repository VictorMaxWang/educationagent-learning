package com.demo.service.tools.holder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户最近发送的图片缓存
 * <ul>
 *   <li>单张：供 {@code ImageEditTool} 读取原图做图生图编辑（{@link #get()}）</li>
 *   <li>多张累计：供小红书发布等需要多图场景（{@link #getAllImages()}）</li>
 * </ul>
 * MessagePreprocessor / DesktopController 处理图片时写入，发布工具用后 {@link #clear()} 清空。
 */
@Slf4j
@Component
public class UserImageHolder {

    /** 最近一张图片（图生图编辑用） */
    private byte[] lastImageBytes;

    /** 累计的待发布图片（小红书多图发布用，按到达顺序） */
    private final List<byte[]> pendingImages = new ArrayList<>();

    /** 存入用户最新发送的图片字节 */
    public void set(byte[] bytes) {
        this.lastImageBytes = bytes;
        pendingImages.add(bytes);
        log.info("🖼️ UserImageHolder 已缓存图片：{}KB，累计 {} 张", bytes.length / 1024, pendingImages.size());
    }

    /** 获取用户最新发送的图片字节（单张，图生图编辑用） */
    public byte[] get() {
        return lastImageBytes;
    }

    /** 获取累计的待发布图片列表（快照） */
    public List<byte[]> getAllImages() {
        return new ArrayList<>(pendingImages);
    }

    /** 清空缓存（发布成功后调用） */
    public void clear() {
        this.lastImageBytes = null;
        pendingImages.clear();
        log.info("🖼️ UserImageHolder 已清空");
    }
}
