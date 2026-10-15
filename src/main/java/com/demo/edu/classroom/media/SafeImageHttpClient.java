package com.demo.edu.classroom.media;

import com.demo.edu.classroom.ClassroomCancellation;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 只访问公共 HTTPS 地址的图片传输层。
 * 每次连接使用已校验且固定的 DNS 结果，重定向由本层逐跳重验，防止 DNS 重绑定绕过地址校验。
 * 下载请求从不携带模型密钥，异常也不包含带签名的图片地址。
 */
public final class SafeImageHttpClient implements ClassroomImageTransport {
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 12 * 1024 * 1024;
    private static final okhttp3.MediaType JSON = okhttp3.MediaType.get("application/json; charset=utf-8");

    @FunctionalInterface
    public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }

    private final Resolver resolver;
    private final OkHttpClient client;

    public SafeImageHttpClient() { this(InetAddress::getAllByName); }

    /** resolver 是 DNS 边界，测试可注入固定地址，生产仍使用系统正常解析。 */
    public SafeImageHttpClient(Resolver resolver) {
        this(resolver, new OkHttpClient());
    }

    /** 测试可在 HTTP 边界注入内存响应；安全连接选项仍由本类统一施加。 */
    SafeImageHttpClient(Resolver resolver, OkHttpClient client) {
        this.resolver = resolver;
        this.client = client.newBuilder().proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(Duration.ofSeconds(15)).readTimeout(Duration.ofSeconds(60)).build();
    }

    @Override
    public byte[] postJson(URI endpoint, String apiKey, byte[] json, ClassroomCancellation token) throws IOException {
        token.check();
        Request request = new Request.Builder().url(endpoint.toString())
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(json, JSON)).build();
        // 生成端点不跟随重定向，凭据仅发送给配置明确指定的那个 origin。
        try (Response response = pinned(endpoint, Duration.ofSeconds(120)).newCall(request).execute()) {
            token.check();
            if (!response.isSuccessful()) throw new IOException("图片生成接口暂不可用");
            return readBounded(response.body(), MAX_JSON_BYTES, token);
        }
    }

    @Override
    public byte[] download(URI image, ClassroomCancellation token) throws IOException {
        URI current = image;
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        for (int redirects = 0; redirects <= 3; redirects++) {
            token.check();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("图片下载超时");
            OkHttpClient target = pinned(current, Duration.ofNanos(remaining));
            Request request = new Request.Builder().url(current.toString()).get().build();
            try (Response response = target.newCall(request).execute()) {
                token.check();
                if (List.of(301, 302, 303, 307, 308).contains(response.code())) {
                    String location = response.header("Location");
                    if (location == null || redirects == 3) throw new IOException("图片重定向无效");
                    try { current = current.resolve(location); }
                    catch (IllegalArgumentException invalid) { throw new IOException("图片重定向无效"); }
                    continue;
                }
                if (!response.isSuccessful()) throw new IOException("图片下载失败");
                return readBounded(response.body(), MAX_IMAGE_BYTES, token);
            }
        }
        throw new IOException("图片重定向过多");
    }

    private OkHttpClient pinned(URI uri, Duration timeout) throws IOException {
        requirePublicHttpsUri(uri);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses == null || addresses.length == 0) throw new IOException("图片地址无法解析");
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) throw new IOException("图片地址必须指向公网");
        }
        List<InetAddress> pinned = List.of(addresses);
        return client.newBuilder().callTimeout(timeout).dns(requested -> {
            if (!requested.equalsIgnoreCase(host)) throw new UnknownHostException("图片连接目标发生变化");
            return pinned;
        }).build();
    }

    public static void requirePublicHttpsUri(URI uri) throws IOException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.toString().contains("\\") || uri.toString().chars().anyMatch(Character::isISOControl)) {
            throw new IOException("图片地址必须为有效公共 HTTPS 地址");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.equals("metadata.google.internal") || host.endsWith(".internal")) {
            throw new IOException("图片地址不能是本机或内网");
        }
    }

    public static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2))))
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    || (a == 203 && b == 0 && c == 113));
        }
        // 仅接收全局单播；排除 ULA、链路本地、IPv4 映射/NAT64 与可嵌入私网地址的过渡机制。
        if (bytes.length != 16 || (a & 0xe0) != 0x20) return false;
        return !(a == 0x20 && (b == 0x02 || (b == 0x01 &&
                (((bytes[2] & 255) == 0 && (bytes[3] & 255) == 0)
                        || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)))));
    }

    private static byte[] readBounded(ResponseBody body, int limit, ClassroomCancellation token) throws IOException {
        if (body == null || body.contentLength() > limit) throw new IOException("图片响应超过大小限制");
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                token.check();
                if (output.size() + read > limit) throw new IOException("图片响应超过大小限制");
                output.write(buffer, 0, read);
            }
            token.check();
            if (output.size() == 0) throw new IOException("图片响应为空");
            return output.toByteArray();
        }
    }
}
