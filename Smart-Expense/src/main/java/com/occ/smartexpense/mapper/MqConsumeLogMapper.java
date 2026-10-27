package com.occ.smartexpense.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 消费幂等记录数据访问。没有自增主键，不用 BaseMapper。
 * insert 成功 = 第一次处理；主键冲突 = 重复投递（调用方捕获 DuplicateKeyException 后直接 ack）。
 */
public interface MqConsumeLogMapper {

    @Insert("INSERT INTO mq_consume_log(msg_id, consumer) VALUES(#{msgId}, #{consumer})")
    int insert(@Param("msgId") String msgId, @Param("consumer") String consumer);

    @Select("SELECT COUNT(*) FROM mq_consume_log WHERE msg_id = #{msgId} AND consumer = #{consumer}")
    int countByMsgIdAndConsumer(@Param("msgId") String msgId, @Param("consumer") String consumer);
}
