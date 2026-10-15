package com.demo.fileconvert.engine;

/**
 * 文档格式互转引擎接口 —— LibreOffice 命令行封装。
 * <p>
 * 支持 doc/docx/xls/xlsx/ppt/pptx/pdf/txt/md/rtf 与 pdf/docx/txt/md/rtf/html 互转。
 */
public interface OfficeConvertService {

    /**
     * 文档格式转换。
     *
     * @param sourceBytes 源文件字节
     * @param sourceExt   源格式扩展名（无点，如 "docx"）
     * @param targetExt   目标格式扩展名（无点，如 "pdf"）
     * @return 转换后的文件字节
     * @throws Exception 转换失败（超时/进程异常/无产物）
     */
    byte[] convert(byte[] sourceBytes, String sourceExt, String targetExt) throws Exception;
}
