package com.demo.edu.classroom;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 只暴露配对状态和短期代码，不返回已绑定的微信标识。 */
@RestController
@RequestMapping("/api/classroom/wechat")
public class ClassroomWechatController {
    private final ClassroomWechatBridge bridge;
    public ClassroomWechatController(ClassroomWechatBridge bridge) { this.bridge = bridge; }

    @GetMapping("/status")
    public Map<String, Object> status() { return bridge.status(); }

    @PostMapping("/pairing")
    public ResponseEntity<?> pairing(@RequestBody(required = false) Map<String, String> body) {
        // 公网入口由服务端配置；客户端baseUrl仅在无配置的本机LAN模式作为待验证提示。
        try { return ResponseEntity.ok(bridge.createPairing(body == null ? null : body.get("baseUrl"))); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
