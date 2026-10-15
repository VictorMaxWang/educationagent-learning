package com.demo.fileconvert.engine;

import com.demo.fileconvert.FileConvertProperties;
import com.demo.fileconvert.TempFileManager;
import com.demo.fileconvert.exception.ConvertProcessException;
import com.demo.fileconvert.exception.FileFormatNotSupportException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * ImageMagick 图片转换实现。
 * <p>
 * 安全设计：
 * <ul>
 *   <li>解码校验：转换前先 magick identify 验证，损坏/捆绑恶意数据直接拦截（FileFormatNotSupportException）</li>
 *   <li>异常体积：超过 50MB 直接拒绝</li>
 *   <li>资源限制：-limit area 256MP -limit thread 2</li>
 *   <li>参数走 List + 超时销毁（{@link CommandRunner}）</li>
 * </ul>
 */
@Slf4j
@Service
public class ImageConvertServiceImpl implements ImageConvertService {

    /** 图片体积上限 50MB */
    private static final long MAX_IMAGE_BYTES = 50L * 1024 * 1024;

    @Resource
    private FileConvertProperties props;

    @Resource
    private TempFileManager tempFileManager;

    @Override
    public byte[] convert(byte[] sourceBytes, String sourceExt, String targetExt) throws Exception {
        FileConvertProperties.ImageMagick im = props.getIm();
        String magick = resolveMagick(im);
        if (magick == null) {
            throw new ConvertProcessException("未找到 ImageMagick（magick），请检查 file.convert.im.path 配置");
        }
        if (sourceBytes.length > MAX_IMAGE_BYTES) {
            throw new FileFormatNotSupportException("图片体积超过 50MB 限制，无法转换");
        }

        Path src = tempFileManager.writeTempFile("image", sourceExt, sourceBytes);
        Path outDir = tempFileManager.outputDir("image");
        String srcName = src.getFileName().toString();
        String outName = srcName.substring(0, srcName.lastIndexOf('.')) + "." + targetExt;
        Path out = outDir.resolve(outName);
        try {
            // 1. 解码校验：损坏/格式不符 → 拒绝
            try {
                CommandRunner.run(List.of(magick, "identify", src.toString()),
                        im.getTimeout(), "ImageMagick");
            } catch (ConvertProcessException e) {
                throw new FileFormatNotSupportException("图片解码失败，文件可能损坏或格式不符：" + e.getMessage());
            }

            // 2. 转换：目标格式由输出文件后缀决定
            List<String> cmd = new ArrayList<>(List.of(
                    magick,
                    "-limit", "area", "256MP",
                    "-limit", "thread", "2",
                    src.toString(), out.toString()));
            log.info("🔄 ImageMagick 转换: {} → {} | {}", sourceExt, targetExt, magick);
            CommandRunner.run(cmd, im.getTimeout(), "ImageMagick");

            if (!Files.exists(out)) {
                throw new ConvertProcessException("ImageMagick 未产出转换文件");
            }
            byte[] result = Files.readAllBytes(out);
            tempFileManager.safeDelete(out);
            return result;
        } finally {
            if (props.isCleanTemp()) {
                tempFileManager.safeDelete(src);
            }
        }
    }

    /** 解析 magick 可执行文件路径：优先 {path}/magick.exe，退回 PATH 中的 cmd */
    private String resolveMagick(FileConvertProperties.ImageMagick im) {
        String base = im.getPath();
        if (base != null && !base.isBlank()) {
            Path exe = Paths.get(base, "magick.exe");
            if (Files.exists(exe)) return exe.toString();
        }
        return im.getCmd();
    }
}
