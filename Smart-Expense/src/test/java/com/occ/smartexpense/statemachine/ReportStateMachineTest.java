package com.occ.smartexpense.statemachine;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.enums.ReportEvent;
import com.occ.smartexpense.enums.ReportStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机单测：覆盖 docs/02-状态机.md §3 的全部合法流转与关键非法流转。
 */
class ReportStateMachineTest {

    private final ReportStateMachine stateMachine = new ReportStateMachine();

    @Test
    void submit_moves_draft_to_pending() {
        assertEquals(ReportStatus.PENDING,
                stateMachine.transit(ReportStatus.DRAFT, ReportEvent.SUBMIT));
    }

    @Test
    void withdraw_moves_pending_back_to_draft() {
        assertEquals(ReportStatus.DRAFT,
                stateMachine.transit(ReportStatus.PENDING, ReportEvent.WITHDRAW));
    }

    @Test
    void approve_non_last_step_stays_approving() {
        assertEquals(ReportStatus.APPROVING, stateMachine.approve(ReportStatus.PENDING, false));
        assertEquals(ReportStatus.APPROVING, stateMachine.approve(ReportStatus.APPROVING, false));
    }

    @Test
    void approve_last_step_moves_to_approved() {
        assertEquals(ReportStatus.APPROVED, stateMachine.approve(ReportStatus.PENDING, true));
        assertEquals(ReportStatus.APPROVED, stateMachine.approve(ReportStatus.APPROVING, true));
    }

    @Test
    void reject_moves_to_rejected() {
        assertEquals(ReportStatus.REJECTED,
                stateMachine.transit(ReportStatus.PENDING, ReportEvent.REJECT));
        assertEquals(ReportStatus.REJECTED,
                stateMachine.transit(ReportStatus.APPROVING, ReportEvent.REJECT));
    }

    @Test
    void pay_success_moves_approved_to_paid() {
        assertEquals(ReportStatus.PAID,
                stateMachine.transit(ReportStatus.APPROVED, ReportEvent.PAY_SUCCESS));
    }

    @Test
    void illegal_transitions_are_rejected() {
        // 终态不能再流转
        assertIllegal(() -> stateMachine.transit(ReportStatus.PAID, ReportEvent.SUBMIT));
        assertIllegal(() -> stateMachine.transit(ReportStatus.REJECTED, ReportEvent.APPROVE));
        // 已有人审批时撤回入口关闭（docs/02 §3 T2）
        assertIllegal(() -> stateMachine.transit(ReportStatus.APPROVING, ReportEvent.WITHDRAW));
        // 跳级
        assertIllegal(() -> stateMachine.transit(ReportStatus.DRAFT, ReportEvent.PAY_SUCCESS));
        assertIllegal(() -> stateMachine.transit(ReportStatus.APPROVED, ReportEvent.SUBMIT));
        // 审批事件不能作用于非审批状态
        assertIllegal(() -> stateMachine.approve(ReportStatus.DRAFT, true));
        assertIllegal(() -> stateMachine.approve(ReportStatus.APPROVED, true));
    }

    @Test
    void terminal_states_have_no_events() {
        assertTrue(stateMachine.eventsOf(ReportStatus.PAID).isEmpty());
        assertTrue(stateMachine.eventsOf(ReportStatus.REJECTED).isEmpty());
    }

    private void assertIllegal(Runnable action) {
        BizException ex = assertThrows(BizException.class, action::run);
        assertEquals(ErrorCode.STATE_ILLEGAL, ex.getErrorCode());
    }
}
