package com.poc.aiassistant.controller;

import com.poc.aiassistant.service.GraphApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/graph")
public class AdminGraphController {

    private final GraphApplicationService graphApplicationService;

    public AdminGraphController(
            GraphApplicationService graphApplicationService
    ) {
        this.graphApplicationService = graphApplicationService;
    }

    @GetMapping("/users")
    public String getUsers() {
        return graphApplicationService.getUsers();
    }
}