package cn.ybcase.bureau.spi;

import java.util.Set;

/** Receiving a transfer requires an existing business role; ADMIN can manage its own created cases. */
public final class CaseOwnerRoles {
    private CaseOwnerRoles() {}
    public static final Set<String> CODES = Set.of("HANDLER", "LEADER", "LEGAL", "ADMIN");
    public static final Set<String> RECEIVER_CODES = Set.of("HANDLER", "LEADER", "LEGAL");
}
