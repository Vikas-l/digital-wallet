package com.digitalwallet.rewards.rule;

import java.util.List;

import com.digitalwallet.rewards.ai.AiRuleGenerator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rules")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Reward rules (admin)")
public class RuleController {

    public record StatusRequest(@NotNull Boolean active) {
    }

    public record GenerateRequest(@NotBlank @Size(max = 1000) String instruction) {
    }

    private final RuleService ruleService;
    private final AiRuleGenerator aiRuleGenerator;

    public RuleController(RuleService ruleService, AiRuleGenerator aiRuleGenerator) {
        this.ruleService = ruleService;
        this.aiRuleGenerator = aiRuleGenerator;
    }

    @GetMapping
    @Operation(summary = "List all rules, highest priority first")
    public List<RuleResponse> list() {
        return ruleService.list();
    }

    @GetMapping("/{id}")
    public RuleResponse get(@PathVariable Long id) {
        return ruleService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a rule (active immediately)")
    public RuleResponse create(@Valid @RequestBody RuleRequest request) {
        return ruleService.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a rule's settings (the code cannot change)")
    public RuleResponse update(@PathVariable Long id, @Valid @RequestBody RuleRequest request) {
        return ruleService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate a rule")
    public RuleResponse setStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return ruleService.setActive(id, request.active());
    }

    @PostMapping("/ai-draft")
    @Operation(summary = "Draft a rule from plain English with an LLM; review it, then POST /api/rules to save")
    public RuleRequest aiDraft(@Valid @RequestBody GenerateRequest request) {
        return aiRuleGenerator.generate(request.instruction());
    }
}
