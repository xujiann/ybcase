package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.spi.GatewaySignatureProvider;
import cn.ybcase.bureau.spi.SignatureProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Simulation evidence is retained as simulation; switching mode never promotes old records. */
@Service
@RequiredArgsConstructor
public class RuntimePolicy {
    private final Environment environment;

    public boolean isFormal() {
        String mode = environment.getProperty("ybcase.runtime.mode", "SIMULATION").strip();
        if (!"SIMULATION".equals(mode) && !"FORMAL".equals(mode))
            throw new BizException(2193, "运行模式须明确配置为 SIMULATION 或 FORMAL");
        return "FORMAL".equals(mode);
    }

    public Map<String, Object> diagnostics() {
        boolean formal = isFormal();
        return Map.of("mode", formal ? "FORMAL" : "SIMULATION", "formal", formal,
                "notice", formal ? "正式模式：新签章及电子回证须满足正式证据要求"
                        : "模拟模式：摘要签章和模拟回证用于联调，不能替代正式签章与送达证据");
    }

    public void requireProvider(SignatureProvider provider) {
        if (isFormal() && !(provider instanceof GatewaySignatureProvider))
            throw new BizException(2193, "正式模式未配置真实 CA 提供者，不能使用模拟摘要签章");
    }

    public boolean formalAccepted(String provider, boolean valid) {
        return valid && isFormal() && GatewaySignatureProvider.PROVIDER.equals(provider);
    }
}
