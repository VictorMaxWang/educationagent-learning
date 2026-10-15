package com.demo.agent;

import com.demo.service.tools.ImageEditService;
import com.demo.service.tools.holder.UserImageHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 图生图 / 图像编辑
 * Agent 根据用户语义自主调用，对用户发送的原始图片进行编辑修改
 * 支持：风格迁移（照片→动漫/油画）、换背景、改颜色、局部修改等
 * 需要用户先发送一张原始图片，再描述修改需求
 * Tool 缓存编辑后的图片字节，由 WechatMsgListener 调用 sendImage 发送
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageEditTool {

    private final ImageEditService imageEditService;
    private final UserImageHolder userImageHolder;

    // 最近一次编辑生成的图片字节；WechatMsgListener 读取后发送
    @Getter
    private byte[] lastEditedImage;

    @Tool("图生图/图像编辑。当用户要求修改、编辑、变换风格、美化已发送的图片时调用此工具。"
            + "例如：'把这张图改成动漫风格'、'把背景换成海滩'、'给图片加上月亮'"
            + "注意：用户必须先发送一张原始图片，再描述编辑需求。"
            + "如果用户只说了编辑需求但没有先发图片，请先让用户发送图片。")
    public String editImage(
            @P("图片编辑描述，如：转换成动漫风格、把背景换成海滩、给图片加上月亮、改成油画风格等。越详细效果越好")
            String prompt) {

        log.info("🎨 Agent 调用图生图工具 | prompt={}", prompt);

        // 获取用户最新发送的原始图片
        byte[] originalImage = userImageHolder.get();
        if (originalImage == null || originalImage.length == 0) {
            log.warn("未找到用户发送的原始图片");
            return "请先发送一张原始图片，再告诉我你想怎么修改～";
        }

        clear();
        byte[] editedImage = imageEditService.editImage(originalImage, prompt);

        if (editedImage == null || editedImage.length == 0) {
            log.warn("图片编辑失败");
            return "图片编辑失败，请稍后重试或换一个描述～";
        }

        this.lastEditedImage = editedImage;
        log.info("✅ 图生图完成 | {}KB", editedImage.length / 1024);
        return "图片已编辑完成，已发送给你，请查收！";
    }

    /** 清除缓存的编辑结果图片 */
    public void clear() {
        this.lastEditedImage = null;
    }
}
