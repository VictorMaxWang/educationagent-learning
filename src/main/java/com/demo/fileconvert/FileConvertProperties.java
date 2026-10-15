package com.demo.fileconvert;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件转换配置绑定类 —— 映射 application.properties 中 file.convert.* 全部配置项。
 * <p>
 * 对应模块配置：
 * <pre>
 * file.convert.libreoffice.cmd=soffice
 * file.convert.libreoffice.path=D:/driver/LibreOffice
 * file.convert.libreoffice.timeout=30000
 * file.convert.libreoffice.temp-dir=...
 * file.convert.im.cmd=magick
 * file.convert.im.path=D:/driver/ImageMagick-7.1.2-Q16-HDRI
 * file.convert.im.timeout=20000
 * file.convert.im.temp-dir=...
 * file.convert.temp-root=...
 * file.convert.clean-temp=true
 * file.convert.clean-expire-minutes=60
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "file.convert")
public class FileConvertProperties {

    /** LibreOffice 文档转换配置 */
    private LibreOffice libreoffice = new LibreOffice();

    /** ImageMagick 图片转换配置 */
    private ImageMagick im = new ImageMagick();

    /** 临时文件根目录 */
    private String tempRoot = System.getProperty("java.io.tmpdir") + "/file_convert_root";

    /** 转换完成后是否清理原始临时文件 */
    private boolean cleanTemp = true;

    /** 临时文件过期时间（分钟） */
    private int cleanExpireMinutes = 60;

    /** LibreOffice 子配置 */
    @Data
    public static class LibreOffice {
        /** soffice 命令（PATH 中可发现时使用） */
        private String cmd = "soffice";
        /** 安装目录（自动探测 {path}/program/soffice.bin 或 soffice.exe） */
        private String path = "D:/LibreOffice";
        /** 转换超时（毫秒），超时强制销毁进程 */
        private long timeout = 30000;
        /** 转换临时目录 */
        private String tempDir = System.getProperty("java.io.tmpdir") + "/office_convert";
    }

    /** ImageMagick 子配置 */
    @Data
    public static class ImageMagick {
        /** magick 命令（PATH 中可发现时使用） */
        private String cmd = "magick";
        /** 安装目录（自动探测 {path}/magick.exe） */
        private String path = "D:/ImageMagick";
        /** 转换超时（毫秒），超时强制销毁进程 */
        private long timeout = 20000;
        /** 转换临时目录 */
        private String tempDir = System.getProperty("java.io.tmpdir") + "/image_convert";
    }
}
