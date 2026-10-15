package com.demo.edu.classroom.media;

import com.demo.edu.classroom.ClassroomCancellation;

import java.io.IOException;
import java.net.URI;

/** 图片服务的 HTTP 边界，便于使用内存响应测试，不让测试读取密钥或访问付费接口。 */
public interface ClassroomImageTransport {
    byte[] postJson(URI endpoint, String apiKey, byte[] json, ClassroomCancellation token) throws IOException;
    byte[] download(URI image, ClassroomCancellation token) throws IOException;
}
