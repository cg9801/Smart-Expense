package com.occ.smartexpense.service;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.vo.ReportDetailVO;
import com.occ.smartexpense.vo.ReportVO;
import com.occ.smartexpense.vo.TimelineVO;

import java.util.List;
import java.util.Set;

/**
 * 报销单业务接口：只定义"做什么"，不出现 SQL 与 HTTP 细节。
 */
public interface ReportService {

    /** 创建草稿，返回详情；对应 docs/02-状态机.md 的初始态 DRAFT。 */
    ReportDetailVO createDraft(ReportCreateDTO dto);

    /** 查询详情，不存在抛 BizException(NOT_FOUND)。 */
    ReportDetailVO getDetail(Long id);

    /**
     * 分页列表（docs/03 §2.2 GET /reports）。
     * scope 权限：dept 需要主管/财务/分管/管理员；all 需要财务/管理员（前端传什么都不越权）。
     */
    PageResult<ReportVO> listReports(Long userId, Long deptId, Set<String> roles,
                                     String scope, String status, String keyword, int page, int size);

    /**
     * 提交报销单（T1 DRAFT → PENDING）。
     * 同一 Idempotency-Key 重复提交直接回放详情，不重复生成审批链。
     *
     * @param id             报销单 id
     * @param idempotencyKey 请求头 Idempotency-Key，落库到 submit_token
     * @param traceId        请求头 X-Trace-Id，为空时服务端生成
     */
    ReportDetailVO submitReport(Long id, String idempotencyKey, String traceId);

    /**
     * 撤回报销单（T2 PENDING → DRAFT），仅当操作人为申请人且没有任何审批动作时允许。
     *
     * @param id         报销单 id
     * @param operatorId 当前登录用户 id，必须等于申请人（docs/02 §3 T2 守卫①）
     * @param traceId    请求头 X-Trace-Id，为空时服务端生成
     */
    ReportDetailVO withdrawReport(Long id, Long operatorId, String traceId);

    /**
     * 编辑草稿（PUT /reports/{id}）：仅本人 + DRAFT；明细整体替换，金额按明细重算。
     * 非草稿或非本人一律拒绝，不做静默忽略。
     */
    ReportDetailVO updateDraft(Long id, ReportCreateDTO dto, Long operatorId);

    /** 审批轨迹（GET /reports/{id}/timeline）：只追加的审计日志，按时间正序。 */
    List<TimelineVO> getTimeline(Long id);

    /**
     * 删除草稿（DELETE /reports/{id}）：逻辑删除，仅本人 + DRAFT。
     * 删除不是状态流转，不进状态机表（docs/02 §3 补记）；发票解绑后可复用。
     */
    void deleteDraft(Long id, Long operatorId);
}
