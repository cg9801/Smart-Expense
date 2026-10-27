package com.occ.smartexpense.service;

import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.vo.ReportDetailVO;

/**
 * 报销单业务接口：只定义"做什么"，不出现 SQL 与 HTTP 细节。
 */
public interface ReportService {

    /** 创建草稿，返回详情；对应 docs/02-状态机.md 的初始态 DRAFT。 */
    ReportDetailVO createDraft(ReportCreateDTO dto);

    /** 查询详情，不存在抛 BizException(NOT_FOUND)。 */
    ReportDetailVO getDetail(Long id);

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
     * 撤回报销单（T2 PENDING → DRAFT），仅当没有任何审批动作时允许。
     *
     * @param id      报销单 id
     * @param traceId 请求头 X-Trace-Id，为空时服务端生成
     */
    ReportDetailVO withdrawReport(Long id, String traceId);
}
