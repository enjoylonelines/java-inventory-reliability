package com.inventory;

import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxMapper {
    @Insert("INSERT INTO inv_outbox(id, transaction_id, request_key, fault) VALUES (#{id}, #{transactionId}, #{key}, #{fault})")
    int insert(@Param("id") UUID id, @Param("transactionId") UUID transactionId,
               @Param("key") String key, @Param("fault") String fault);
}
