package com.demo.edu;

import com.demo.edu.auth.PasswordHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码哈希测试。
 *
 * <p>教师账号密码是这套系统里唯一的"秘密"，而它最容易在实现里被做错成两种样子：
 * 明文落库、或用固定盐（同一个密码产生同一个哈希，可被彩虹表批量破解）。
 * 这两条都在这里钉住。
 */
class PasswordHasherTest {

    @Test
    @DisplayName("哈希后可校验，明文不落库")
    void hashThenVerify() {
        String stored = PasswordHasher.hash("teacher123");
        assertNotEquals("teacher123", stored, "绝不能存明文");
        assertTrue(stored.startsWith("pbkdf2$"), "应带算法标识，便于将来升级迭代次数");
        assertTrue(PasswordHasher.isHashed(stored));
        assertTrue(PasswordHasher.verify("teacher123", stored));
        assertFalse(PasswordHasher.verify("teacher124", stored));
    }

    @Test
    @DisplayName("同一密码两次哈希结果不同（随机盐）")
    void usesRandomSalt() {
        String a = PasswordHasher.hash("same-password");
        String b = PasswordHasher.hash("same-password");
        assertNotEquals(a, b, "固定盐会让相同密码产生相同哈希，撞库成本骤降");
        assertTrue(PasswordHasher.verify("same-password", a));
        assertTrue(PasswordHasher.verify("same-password", b));
    }

    @Test
    @DisplayName("异常输入不抛异常、一律判失败")
    void handlesBadInput() {
        assertFalse(PasswordHasher.verify("x", null));
        assertFalse(PasswordHasher.verify(null, "pbkdf2$1$a$b"));
        assertFalse(PasswordHasher.verify("x", "plaintext-password"), "库里若存了明文，不得当成校验通过");
        assertFalse(PasswordHasher.verify("x", "pbkdf2$abc$def"), "格式非法应判失败而不是抛异常");
        assertFalse(PasswordHasher.isHashed("plaintext-password"));
        assertFalse(PasswordHasher.isHashed(null));
    }
}
