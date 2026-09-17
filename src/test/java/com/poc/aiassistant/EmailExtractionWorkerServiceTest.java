package com.poc.aiassistant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.ExtractedTask;
import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.entity.EmailProcessing;
import com.poc.aiassistant.entity.EmailProcessingStatus;
import com.poc.aiassistant.entity.TaskPriority;
import com.poc.aiassistant.repository.EmailProcessingRepository;
import com.poc.aiassistant.repository.EmailQueueDao;
import com.poc.aiassistant.service.EmailExtractionWorkerService;
import com.poc.aiassistant.service.EmailProcessingService;
import com.poc.aiassistant.service.EmailTaskService;
import com.poc.aiassistant.service.LlmRateLimiterService;
import com.poc.aiassistant.service.TenantGraphEmailService;

class EmailExtractionWorkerServiceTest {

    private EmailQueueDao queueDao;
    private EmailProcessingRepository repository;
    private EmailProcessingService processingService;
    private TenantGraphEmailService graphService;
    private EmailTaskService taskService;
    private LlmRateLimiterService limiter;
    private EmailExtractionWorkerService worker;

    @BeforeEach
    void setUp() {
        queueDao = mock(EmailQueueDao.class);
        repository = mock(EmailProcessingRepository.class);
        processingService = mock(EmailProcessingService.class);
        graphService = mock(TenantGraphEmailService.class);
        taskService = mock(EmailTaskService.class);
        limiter = mock(LlmRateLimiterService.class);

        worker = new EmailExtractionWorkerService(
                queueDao,
                repository,
                processingService,
                graphService,
                taskService,
                limiter,
                3,
                100
        );
    }

    @Test
    void claimSizeIsCappedByRemainingBudget() {
        when(limiter.remainingThisMinute()).thenReturn(2);
        when(limiter.remainingToday()).thenReturn(10);
        when(queueDao.claimBatch(anyInt(), eq(3), eq(100), anyString())).thenReturn(List.of());

        worker.runOnce(20);

        verify(queueDao).claimBatch(eq(2), eq(3), eq(100), anyString());
    }

    @Test
    void noBudgetMeansNoClaim() {
        when(limiter.remainingThisMinute()).thenReturn(0);
        when(limiter.remainingToday()).thenReturn(10);

        worker.runOnce(20);

        verify(queueDao, never()).claimBatch(anyInt(), anyInt(), anyInt(), anyString());
    }

   @Test
    void successfulClaimUsesExistingCompletionService() {
        when(limiter.remainingThisMinute()).thenReturn(1);
        when(limiter.remainingToday()).thenReturn(1);
        when(queueDao.claimBatch(eq(1), eq(3), eq(100), anyString()))
                .thenReturn(List.of(7L));

        EmailProcessing processing = mock(EmailProcessing.class);

        when(processing.getId()).thenReturn(7L);
        when(processing.getStatus())
                .thenReturn(EmailProcessingStatus.PROCESSING);
        when(processing.getMessageId())
                .thenReturn("m1");
        when(processing.getMailboxUserId())
                .thenReturn("u1");

        when(repository.findAllById(List.of(7L)))
                .thenReturn(List.of(processing));

        EmailDto email = new EmailDto(
                "m1",
                "Subject",
                "Sender",
                "sender@test",
                "test",
                "INTERNAL",
                "2026-08-27T10:00:00Z",
                "body",
                List.of(),
                List.of(),
                "u1",
                null
        );

        when(graphService.getMessage("u1", "m1"))
                .thenReturn(email);

        when(taskService.extractIntelligenceForQueue(email))
                .thenReturn(new com.poc.aiassistant.dto.TaskExtractionResult(
                        true,
                        List.of(
                                new ExtractedTask(
                                        "Do work",
                                        "Description",
                                        null,
                                        TaskPriority.MEDIUM,
                                        "Sender",
                                        "sender@test"
                                )
                        ),
                        false
                ));

        when(processingService.completeSuccessfully(
                anyLong(),
                anyString(),
                eq(email),
                any(),
                anyBoolean(),
                anyBoolean()
        )).thenReturn(List.<TaskDto>of());

        EmailExtractionWorkerService.CycleResult result =
                worker.runOnce(20);

        assertEquals(1, result.claimed());
        assertEquals(1, result.completed());

        verify(limiter).acquireOrThrow();

        verify(processingService)
                .markAttemptStarted(eq(7L), anyString());

        verify(processingService)
                .completeSuccessfully(
                        eq(7L),
                        anyString(),
                        eq(email),
                        any(),
                        anyBoolean(),
                        anyBoolean()
                );
    }
}
