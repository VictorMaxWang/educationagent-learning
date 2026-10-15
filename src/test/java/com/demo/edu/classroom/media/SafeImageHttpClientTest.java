package com.demo.edu.classroom.media;

import com.demo.edu.classroom.ClassroomCancellation;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** DNS 和 HTTP 都在边界替换为内存实现，所有场景均不连接网络。 */
class SafeImageHttpClientTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1", "2002:7f00:1::1"})
    void rejectsPrivateAndMetadataAddressesBeforeAnyHttpRequest(String address) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var client = new SafeImageHttpClient(host -> new InetAddress[]{InetAddress.getByName(address)},
                new OkHttpClient.Builder().addInterceptor(chain -> { requests.incrementAndGet(); throw new IOException("HTTP must not run"); }).build());
        assertThrows(IOException.class, () -> client.download(URI.create("https://cdn.example.test/image.png"), new ClassroomCancellation()));
        assertEquals(0, requests.get());
    }

    @Test
    void rejectsMixedPublicAndPrivateDnsAnswers() throws Exception {
        var client = new SafeImageHttpClient(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.1")});
        assertThrows(IOException.class, () -> client.download(URI.create("https://cdn.example.test/image.png"), new ClassroomCancellation()));
        assertTrue(SafeImageHttpClient.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(SafeImageHttpClient.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://cdn.example.test/a", "file:///tmp/a", "https://user:pass@cdn.example.test/a", "https://cdn.example.test:8443/a", "https://cdn.example.test/a#fragment", "https://metadata.google.internal/a", "https://localhost/a"})
    void rejectsUnsupportedUris(String uri) {
        assertThrows(IOException.class, () -> SafeImageHttpClient.requirePublicHttpsUri(URI.create(uri)));
    }

    @Test
    void rechecksRedirectDnsAndNeverForwardsProviderAuthorization() throws Exception {
        List<String> resolved = new ArrayList<>();
        AtomicInteger requests = new AtomicInteger();
        var http = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            assertNull(chain.request().header("Authorization"));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                    .header("Location", "https://hidden.example.test/metadata")
                    .body(ResponseBody.create(new byte[0], null)).build();
        }).build();
        var client = new SafeImageHttpClient(host -> {
            resolved.add(host);
            return new InetAddress[]{InetAddress.getByName(host.equals("cdn.example.test") ? "8.8.8.8" : "169.254.169.254")};
        }, http);
        assertThrows(IOException.class, () -> client.download(URI.create("https://cdn.example.test/a"), new ClassroomCancellation()));
        assertEquals(List.of("cdn.example.test", "hidden.example.test"), resolved);
        assertEquals(1, requests.get());
    }

    @Test
    void providerPostDoesNotFollowRedirectAndOversizedDownloadsAreRejected() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var redirect = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            assertEquals("Bearer test-only", chain.request().header("Authorization"));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(307).message("Redirect")
                    .header("Location", "https://other.example.test/a")
                    .body(ResponseBody.create(new byte[0], null)).build();
        }).build();
        var client = new SafeImageHttpClient(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")}, redirect);
        assertThrows(IOException.class, () -> client.postJson(URI.create("https://provider.example.test/images"), "test-only",
                "{}".getBytes(StandardCharsets.UTF_8), new ClassroomCancellation()));
        assertEquals(1, requests.get());

        var tooLarge = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(new byte[SafeImageHttpClient.MAX_IMAGE_BYTES + 1], null)).build()).build();
        var bounded = new SafeImageHttpClient(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")}, tooLarge);
        assertThrows(IOException.class, () -> bounded.download(URI.create("https://cdn.example.test/a"), new ClassroomCancellation()));
    }
}
