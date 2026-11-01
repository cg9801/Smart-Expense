package com.occ.smartexpense.vo;

import lombok.Data;

/**
 * 转交候选人（下拉用）：只给前端展示所需的三个字段。
 * 防环由服务端再校验一次，前端过滤只是体验优化（docs/02 §5）。
 */
@Data
public class TransferCandidateVO {

    private Long userId;

    private String realName;

    private String deptName;
}
