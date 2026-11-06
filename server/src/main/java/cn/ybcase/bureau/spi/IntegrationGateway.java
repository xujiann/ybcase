package cn.ybcase.bureau.spi;

import cn.ybcase.bureau.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;

/** Explicitly configured gateway contract. No redirects, automatic retries or response logging. */
@Component
@RequiredArgsConstructor
public class IntegrationGateway {
    private static final int MAX_RESPONSE = 1024 * 1024;
    private final Environment environment;
    private final ObjectMapper mapper;

    public boolean configured(String target) {
        return "true".equalsIgnoreCase(property(target, "enabled")) && !property(target, "url").isBlank();
    }

    public JsonNode post(String target, String path, Map<String, ?> body, String requestKey) {
        if (!configured(target)) throw new BizException(2160, "外部网关未启用或未配置");
        URI url = endpoint(target, path);
        String token = property(target, "token");
        if (token.isBlank() || token.codePoints().anyMatch(Character::isISOControl))
            throw new BizException(2160, "外部网关凭证未配置或格式无效");
        HttpURLConnection connection = null;
        try {
            byte[] request = mapper.writeValueAsBytes(body);
            connection = (HttpURLConnection) url.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(request.length);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (requestKey != null) connection.setRequestProperty("Idempotency-Key", requestKey);
            try (var output = connection.getOutputStream()) {
                output.write(request);
            }
            int status = connection.getResponseCode();
            if (status != 200)
                throw new BizException(2161, "外部网关请求失败，请通过网关记录核对状态");
            try (var input = connection.getInputStream()) {
                byte[] data = input.readNBytes(MAX_RESPONSE + 1);
                if (data.length > MAX_RESPONSE) throw invalidResponse();
                JsonNode result = mapper.reader()
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .readTree(data);
                if (result == null || !result.isObject()) throw invalidResponse();
                return result;
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new BizException(2161, "外部网关请求失败或响应格式无效");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private URI endpoint(String target, String path) {
        String value = property(target, "url");
        if (value.isBlank()) throw new BizException(2160, "外部网关未配置");
        try {
            URI base = URI.create(value);
            String host = base.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
            if (host == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                    || base.getRawFragment() != null
                    || !("https".equalsIgnoreCase(base.getScheme()) || loopback && "http".equalsIgnoreCase(base.getScheme())))
                throw new IllegalArgumentException();
            return URI.create((value.endsWith("/") ? value : value + "/") + path);
        } catch (IllegalArgumentException e) {
            throw new BizException(2160, "外部网关地址须为 HTTPS 或本机联调 HTTP 地址");
        }
    }

    private String property(String target, String key) {
        return environment.getProperty("ybcase.integration." + target + "." + key, "").strip();
    }

    public static BizException invalidResponse() {
        return new BizException(2161, "外部网关响应与接口约定不一致");
    }

    public static String text(JsonNode node, String key, int max) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max
                || value.textValue().indexOf(0) >= 0) throw invalidResponse();
        return value.textValue();
    }
}
