package com.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class WeatherDiag {
    public static void main(String[] args) {
        try {
            // Test 1: JDK HttpClient
            System.out.println("=== Test 1: JDK HttpClient ===");
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://m53wt4cm65.re.qweatherapi.com/v7/weather/now?location=101010100&key=2b31bdc813c5445696c7508cdc6e621c"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("Status: " + resp.statusCode());
            System.out.println("Headers: " + resp.headers().map());
            System.out.println("Body: " + resp.body());
            System.out.println("=== Test 1 OK ===\n");
        } catch (Exception e) {
            System.out.println("Test 1 FAILED: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        try {
            // Test 2: via Apache HttpClient5 directly
            System.out.println("=== Test 2: Apache HttpClient5 ===");
            try (var httpClient = org.apache.hc.client5.http.impl.classic.HttpClients.createDefault()) {
                var httpGet = new org.apache.hc.client5.http.classic.methods.HttpGet(
                    "https://m53wt4cm65.re.qweatherapi.com/v7/weather/now?location=101010100&key=2b31bdc813c5445696c7508cdc6e621c");
                String result = httpClient.execute(httpGet, response -> {
                    int code = response.getCode();
                    String body = new String(response.getEntity().getContent().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    return "Status: " + code + ", Body: " + body;
                });
                System.out.println(result);
            }
            System.out.println("=== Test 2 OK ===\n");
        } catch (Exception e) {
            System.out.println("Test 2 FAILED: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }
}
