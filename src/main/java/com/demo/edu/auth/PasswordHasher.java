package com.demo.edu.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 密码哈希：PBKDF2-HMAC-SHA256 + 随机盐。
 *
 * <h3>为什么自己写而不引 bcrypt 依赖</h3>
 * {@code PBKDF2WithHmacSHA256} 是 JDK 自带的（{@code SecretKeyFactory}），
 * 而引入 spring-security-crypto / jbcrypt 只是为了一个哈希函数。
 * 本项目整体没有 spring-security（也不需要——鉴权就这一处），
 * 为一行哈希拉进一整个依赖树不划算。
 *
 * <p>存储格式：{@code pbkdf2$<迭代次数>$<盐base64>$<哈希base64>}。
 * 迭代次数写在串里，将来调大迭代数时老密码仍能验证（按串里的参数算），无需强制改密。
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    /** 迭代次数：约 100~200ms/次，登录场景可接受，暴力破解成本足够高。 */
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** 生成可存库的密码串。 */
    public static String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 校验密码。<b>任何解析失败都返回 false</b>（不抛异常），
     * 免得把"格式不对"变成调用方要处理的异常分支。
     */
    public static boolean verify(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword, salt, iterations);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败：" + e.getMessage(), e);
        }
    }

    /** 定长比较，避免用时间差推断哈希前缀。 */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    /** 仅用于测试/诊断：判断某个串是不是本类的哈希格式。 */
    public static boolean isHashed(String value) {
        return value != null && value.startsWith(PREFIX + "$")
                && value.split("\\$").length == 4
                && value.getBytes(StandardCharsets.UTF_8).length > 20;
    }
}
