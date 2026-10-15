package com.demo.fileconvert.engine;

/**
 * 图片格式互转引擎接口 —— ImageMagick 命令行封装。
 * <p>
 * 支持 jpg/jpeg/png/gif/webp/heic/heif/tiff/bmp/jxl 与 jpg/png/webp 互转。
 */
public interface ImageConvertService {

    /**
     * 图片格式转换。
     *
     * @param sourceBytes 源图片字节
     * @param sourceExt   源格式扩展名（无点，如 "png"）
     * @param targetExt   目标格式扩展名（无点，如 "jpg"）
     * @return 转换后的图片字节
     * @throws Exception 转换失败（解码失败/超时/进程异常/体积超限）
     */
    byte[] convert(byte[] sourceBytes, String sourceExt, String targetExt) throws Exception;
}
