package com.inventory.oracle;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.cursor.Cursor;

@Mapper
public interface OracleOutboxMapper {
    // Open/consume/close within one transaction; fetch size one avoids prefetching
    // additional locked rows. Oracle locks SKIP LOCKED rows when fetched.
    @Select("""
        SELECT id, payload, attempts FROM ir_outbox
        WHERE (status = 'PENDING' AND next_attempt_at <= SYSTIMESTAMP)
           OR (status = 'PROCESSING' AND lease_until <= SYSTIMESTAMP)
        ORDER BY next_attempt_at, id FOR UPDATE SKIP LOCKED
        """)
    @Options(fetchSize = 1)
    Cursor<Map<String, Object>> lockReady();

    @Update("""
        UPDATE ir_outbox SET status = 'PROCESSING', attempts = attempts + 1,
          lease_token = #{token}, lease_until = SYSTIMESTAMP + NUMTODSINTERVAL(#{leaseSeconds}, 'SECOND'),
          last_error = NULL
        WHERE id = #{id} AND ((status = 'PENDING' AND next_attempt_at <= SYSTIMESTAMP)
          OR (status = 'PROCESSING' AND lease_until <= SYSTIMESTAMP))
        """)
    int claim(@Param("id") String id, @Param("token") String token, @Param("leaseSeconds") long leaseSeconds);

    @Update("""
        UPDATE ir_outbox SET status = 'DEAD', lease_token = NULL, lease_until = NULL,
          last_error = 'attempt limit reached after lease expiry'
        WHERE id = #{id} AND attempts >= #{maxAttempts}
          AND ((status = 'PROCESSING' AND lease_until <= SYSTIMESTAMP)
            OR (status = 'PENDING' AND next_attempt_at <= SYSTIMESTAMP))
        """)
    int expireAtLimit(@Param("id") String id, @Param("maxAttempts") int maxAttempts);

    @Update("""
        UPDATE ir_outbox SET status = 'SENT', lease_token = NULL, lease_until = NULL,
          completed_at = SYSTIMESTAMP, last_error = NULL
        WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{token} AND lease_until > SYSTIMESTAMP
        """)
    int sent(@Param("id") String id, @Param("token") String token);

    @Update("""
        UPDATE ir_outbox SET status = CASE WHEN attempts >= #{maxAttempts} THEN 'DEAD' ELSE 'PENDING' END,
          next_attempt_at = SYSTIMESTAMP + NUMTODSINTERVAL(#{delaySeconds}, 'SECOND'),
          last_error = #{error}, lease_token = NULL, lease_until = NULL
        WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{token} AND lease_until > SYSTIMESTAMP
        """)
    int fail(@Param("id") String id, @Param("token") String token, @Param("maxAttempts") int maxAttempts,
        @Param("delaySeconds") long delaySeconds, @Param("error") String error);

    @Update("""
        UPDATE ir_outbox SET status = 'PENDING', attempts = 0, next_attempt_at = SYSTIMESTAMP,
          completed_at = NULL, last_error = NULL
        WHERE id = #{id} AND status = 'DEAD'
        """)
    int requeue(@Param("id") String id);

    @Insert("""
        INSERT INTO ir_outbox_history (id, outbox_id, transition, actor, attempt, detail)
        VALUES (#{id}, #{outboxId}, #{transition}, #{actor}, #{attempt}, #{detail,jdbcType=VARCHAR})
        """)
    int history(@Param("id") String id, @Param("outboxId") String outboxId,
        @Param("transition") String transition, @Param("actor") String actor,
        @Param("attempt") int attempt, @Param("detail") String detail);
}
