package cn.ybcase.core.spi;

import java.util.List;

/**
 * 停用账号前置校验扩展点。
 * core 是通用平台层，不知道"案件"为何物，但停用一个名下还有在办事项的账号会让这些事项无人接手；
 * 业务模块实现本接口说明"为什么不能停用"，SysUserController 逐个询问，任一给出阻止理由即拒绝停用。
 */
public interface UserDeactivationGuard {

    /** 返回阻止停用的理由清单（面向管理员的完整提示语）；空/null 表示放行 */
    List<String> blockers(String username);
}
