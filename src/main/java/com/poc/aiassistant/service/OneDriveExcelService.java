package com.poc.aiassistant.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.poc.aiassistant.entity.Task;

@Service
public class OneDriveExcelService {

    private static final String WORKBOOK_PATH =
            "AI-Email-Tasks-v2.xlsx";

    private static final String TABLE_NAME =
            "TasksTable";

    private final GraphApplicationTokenService tokenService;

    private final RestClient restClient;

    /**
     * Graph site ID of the OneDrive-personal site hosting the
     * shared workbook (format: "{hostname},{siteGuid},{webGuid}").
     *
     * /users/{id}/drive can 404 unreliably for OneDrive-personal
     * sites even when the drive clearly exists (confirmed via
     * Graph Explorer), so we address the site directly instead.
     */
    private final String siteId;

    public OneDriveExcelService(
            GraphApplicationTokenService tokenService,
            @Value("${microsoft.graph.base-url}") String graphBaseUrl,
            @Value("${microsoft.excel.site-id}") String siteId
    ) {

        this.tokenService = tokenService;

        this.restClient =
                RestClient.builder()
                        .baseUrl(graphBaseUrl)
                        .build();

        this.siteId = siteId;
    }

    /**
     * Append one task as a new row to TasksTable.
     *
     * Excel columns must be in this exact order:
     *
     * Task
     * Description
     * Assignee
     * To Email
     * Due Date
     * Priority
     * Status
     * Subject
     * From Email
     * Email Received At   (NEW — see note below)
     *
     * IMPORTANT / OPERATIONAL DEPENDENCY: this Graph rows/add call is
     * strictly positional against whatever columns already exist in
     * AI-Email-Tasks-v2.xlsx's TasksTable. Adding "Email Received At"
     * here does NOT create the column in the actual workbook — a
     * person with edit access must add a 10th column named
     * "Email Received At" as the LAST column of TasksTable before
     * this code path is exercised against the real sheet. Until that
     * is done, appendTask will send 10 values against a 9-column
     * table, which the Graph Excel API is expected to reject.
     *
     * Uses an app-only (client credentials) Graph token, the same
     * mechanism already used reliably for mailbox reads. No human
     * login or session is required, so this cannot go stale the
     * way a delegated grant can.
     */
    public void appendTask(
            Task task
    ) {

        if (siteId == null || siteId.isBlank()) {
            throw new IllegalStateException(
                    "microsoft.excel.site-id is not configured"
            );
        }

        String accessToken =
                tokenService.getAccessToken();

        List<List<Object>> values =
                List.of(buildRowValues(task));

        ExcelRowsRequest request =
                new ExcelRowsRequest(
                        null,
                        values
                );

        restClient
                .post()
                .uri(
                        "/sites/{siteId}/drive/root:/{workbook}:/workbook/tables/{table}/rows/add",
                        siteId,
                        WORKBOOK_PATH,
                        TABLE_NAME
                )
                .headers(headers ->
                        headers.setBearerAuth(accessToken)
                )
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Pure row-building logic, split out from appendTask so it can be
     * unit tested without a Graph HTTP call. Column order here MUST
     * match the javadoc on appendTask and the real Excel table.
     */
    public List<Object> buildRowValues(
            Task task
    ) {

        return List.of(
                // Task
                safe(task.getTitle()),

                // Description
                safe(task.getDescription()),

                // Assignee
                safe(task.getAssignee()),

                // To Email
                safe(task.getAssigneeEmail()),

                // Due Date
                task.getDueDate() == null
                        ? ""
                        : task.getDueDate().toString(),

                // Priority
                task.getPriority() == null
                        ? ""
                        : task.getPriority().name(),

                // Status
                task.getStatus() == null
                        ? ""
                        : task.getStatus().name(),

                // Subject
                safe(task.getSourceSubject()),

                // From Email
                safe(task.getSourceSender()),

                // Email Received At — original Graph receivedDateTime,
                // NOT processing/creation/Excel-insertion time.
                task.getEmailReceivedAt() == null
                        ? ""
                        : task.getEmailReceivedAt().toString()
        );
    }

    private String safe(
            String value
    ) {

        return value == null
                ? ""
                : value;
    }

    /**
     * Graph Excel rows/add request.
     */
    private record ExcelRowsRequest(
            Integer index,
            List<List<Object>> values
    ) {
    }
}