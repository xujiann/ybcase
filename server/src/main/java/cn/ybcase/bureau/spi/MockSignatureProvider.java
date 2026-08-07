package cn.ybcase.bureau.spi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Mock 签章：SHA-256 摘要留痕（具备防篡改校验能力，不具备法律效力——实施期换 CA） */
@Component
@ConditionalOnMissingBean(value = SignatureProvider.class, ignored = MockSignatureProvider.class)
public class MockSignatureProvider implements SignatureProvider {

    @Override
    public SignResult sign(String content, String signer) {
        String hash = sha256(content);
        return new SignResult(hash, sha256(hash + "|" + signer), "MOCK");
    }

    @Override
    public boolean verify(String content, String contentHash, String signature) {
        return sha256(content).equals(contentHash);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
