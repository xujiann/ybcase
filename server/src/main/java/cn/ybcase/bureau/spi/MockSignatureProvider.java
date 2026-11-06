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
        // The historical MOCK digest is sha256(hash + "|" + signer). Without its recorded signer
        // the signature itself cannot be checked; a matching content hash alone is not verification.
        return false;
    }

    @Override
    public boolean verify(String content, String contentHash, String signature, String signer, String provider) {
        if (content == null || contentHash == null || signature == null || signer == null || signer.isBlank()
                || !"MOCK".equals(provider)) return false;
        String actualHash = sha256(content);
        return same(actualHash, contentHash) && same(sha256(actualHash + "|" + signer), signature);
    }

    private static boolean same(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
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
