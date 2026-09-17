package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

import com.poc.aiassistant.repository.EmailQueueDao;

class EmailQueueBatchClaimTest {

    @Test
    void claimBatchUsesSkipLockedAndReturnsClaimedIds() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        EmailQueueDao dao = new EmailQueueDao(jdbc);

        when(jdbc.query(
                contains("FOR UPDATE SKIP LOCKED"),
                any(RowMapper.class),
                eq(3),
                eq(100),
                eq(5),
                any(),
                eq("worker-1"),
                any()
        )).thenReturn(List.of(10L, 11L, 12L));

        List<Long> ids = dao.claimBatch(5, 3, 100, "worker-1");

        assertEquals(List.of(10L, 11L, 12L), ids);
        verify(jdbc).query(
                contains("FOR UPDATE SKIP LOCKED"),
                any(RowMapper.class),
                eq(3),
                eq(100),
                eq(5),
                any(),
                eq("worker-1"),
                any()
        );
    }

    @Test
    void invalidBatchArgumentsDoNotHitDatabase() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        EmailQueueDao dao = new EmailQueueDao(jdbc);

        assertTrue(dao.claimBatch(0, 3, 100, "worker").isEmpty());
        assertTrue(dao.claimBatch(5, 3, 100, "").isEmpty());
    }
}
