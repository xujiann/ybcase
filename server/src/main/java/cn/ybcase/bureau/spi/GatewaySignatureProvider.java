package cn.ybcase.bureau.spi;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Component
@Primary
@ConditionalOnProperty(prefix = "ybcase.integration.signature", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class GatewaySignatureProvider implements SignatureProvider {
    public static final String PROVIDER = "CA_GATEWAY";
    private final IntegrationGateway gateway;
    private final MockSignatureProvider legacy = new MockSignatureProvider();

    @Override
    public SignResult sign(String content, String signer) {
        String digest = contentHash(content);
        String key = contentHash(digest + "\n" + signer);
        var response = gateway.post("signature", "signatures/sign",
                Map.of("contentHash", digest, "signer", signer), key);
        if (!digest.equals(IntegrationGateway.text(response, "contentHash", 64))
                || !signer.equals(IntegrationGateway.text(response, "signer", 64)))
            throw IntegrationGateway.invalidResponse();
        return new SignResult(digest, IntegrationGateway.text(response, "signature", 512), PROVIDER);
    }

    @Override
    public boolean verify(String content, String contentHash, String signature) {
        return false;
    }

    @Override
    public boolean verify(String content, String contentHash, String signature, String signer, String provider) {
        if ("MOCK".equals(provider)) return legacy.verify(content, contentHash, signature, signer, provider);
        if (!PROVIDER.equals(provider) || content == null || signer == null || signer.isBlank()
                || signature == null || signature.isBlank() || !contentHash(content).equals(contentHash)) return false;
        var response = gateway.post("signature", "signatures/verify",
                Map.of("contentHash", contentHash, "signature", signature, "signer", signer), null);
        if (!response.path("valid").isBoolean()) throw IntegrationGateway.invalidResponse();
        return response.path("valid").booleanValue();
    }

    public static String contentHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
