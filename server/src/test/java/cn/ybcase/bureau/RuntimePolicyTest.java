package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.RuntimePolicy;
import cn.ybcase.bureau.spi.GatewaySignatureProvider;
import cn.ybcase.bureau.spi.MockSignatureProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePolicyTest {
    @Test void defaultsToSimulationAndNeverPromotesMockEvidence() {
        var policy = new RuntimePolicy(new MockEnvironment());
        assertFalse(policy.isFormal());
        assertDoesNotThrow(() -> policy.requireProvider(new MockSignatureProvider()));
        assertFalse(policy.formalAccepted("MOCK", true));
        assertFalse(policy.formalAccepted(GatewaySignatureProvider.PROVIDER, true));
    }
    @Test void formalModeRefusesMockAndOnlyAcceptsVerifiedGatewayRecords() {
        var policy = new RuntimePolicy(new MockEnvironment().withProperty("ybcase.runtime.mode", "FORMAL"));
        assertTrue(policy.isFormal());
        assertThrows(BizException.class, () -> policy.requireProvider(new MockSignatureProvider()));
        assertFalse(policy.formalAccepted("MOCK", true));
        assertFalse(policy.formalAccepted(GatewaySignatureProvider.PROVIDER, false));
        assertTrue(policy.formalAccepted(GatewaySignatureProvider.PROVIDER, true));
    }
    @Test void invalidConfigurationFailsClosed() {
        var policy = new RuntimePolicy(new MockEnvironment().withProperty("ybcase.runtime.mode", "production"));
        assertThrows(BizException.class, policy::isFormal);
        assertThrows(BizException.class, policy::diagnostics);
    }
}
