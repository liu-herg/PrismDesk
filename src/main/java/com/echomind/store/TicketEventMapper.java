package com.echomind.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TicketEventMapper extends BaseMapper<TicketEventEntity> {

    @Select("SELECT * FROM ticket_event WHERE ticket_id = #{ticketId} ORDER BY id")
    List<TicketEventEntity> selectByTicket(@Param("ticketId") long ticketId);
}
