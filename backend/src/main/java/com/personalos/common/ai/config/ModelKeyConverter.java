package com.personalos.common.ai.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 可选的模型密钥静态加密。旧明文兼容读取，配置主密钥后保存时写入随机 IV 的 AES-GCM 密文。 */
@Component
@Converter
public class ModelKeyConverter implements AttributeConverter<String, String> {
    private static final String PREFIX = "{aesgcm:v1}";
    private static final byte[] AAD = "personal-ai-os:model-api-key:v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public ModelKeyConverter(@Value("${app.model-key-encryption-key:}") String encodedKey) {
        if (encodedKey.isBlank()) {
            key = null;
        } else {
            try {
                byte[] bytes = Base64.getDecoder().decode(encodedKey);
                if (bytes.length != 32) throw new IllegalArgumentException();
                key = new SecretKeySpec(bytes, "AES");
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("MODEL_KEY_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节随机密钥");
            }
        }
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        if (plain == null || key == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (Exception error) {
            throw new IllegalStateException("模型密钥加密失败");
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        if (key == null) throw new IllegalStateException("读取加密模型配置需要 MODEL_KEY_ENCRYPTION_KEY");
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (data.length < 28) throw new IllegalArgumentException();
            byte[] iv = java.util.Arrays.copyOfRange(data, 0, 12);
            return new String(cipher(Cipher.DECRYPT_MODE, iv).doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception error) {
            // 不传播包含敏感参数的底层异常。
            throw new IllegalStateException("模型密钥解密失败，请检查主密钥与备份完整性");
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(AAD);
        return cipher;
    }
}
