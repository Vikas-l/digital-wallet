package com.digitalwallet.rewards.reward;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.digitalwallet.rewards.web.CurrentUser;
import com.digitalwallet.rewards.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rewards")
@Tag(name = "Rewards")
public class RewardController {

    public record RewardResponse(UUID rewardId, UUID sourceTransactionId, String ruleCode,
            BigDecimal transactionAmount, BigDecimal rewardAmount, Instant createdAt) {

        static RewardResponse of(Reward r) {
            return new RewardResponse(r.getId(), r.getSourceTransactionId(), r.getRuleCode(),
                    r.getTransactionAmount(), r.getRewardAmount(), r.getCreatedAt());
        }
    }

    public record RewardSummary(Long userId, long rewardCount, BigDecimal totalCashback) {
    }

    public record LeaderboardResponse(YearMonth month, List<Leaderboard.Entry> entries) {
    }

    private final RewardRepository rewardRepository;
    private final Leaderboard leaderboard;

    public RewardController(RewardRepository rewardRepository, Leaderboard leaderboard) {
        this.rewardRepository = rewardRepository;
        this.leaderboard = leaderboard;
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    @Operation(summary = "Rewards earned by the caller, newest first")
    public PageResponse<RewardResponse> myRewards(@AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(rewardRepository.findByUserIdOrderByCreatedAtDesc(CurrentUser.id(jwt), pageable)
                .map(RewardResponse::of));
    }

    @GetMapping("/me/summary")
    @Transactional(readOnly = true)
    @Operation(summary = "Total cashback earned by the caller")
    public RewardSummary mySummary(@AuthenticationPrincipal Jwt jwt) {
        Long userId = CurrentUser.id(jwt);
        return new RewardSummary(userId, rewardRepository.countByUserId(userId),
                rewardRepository.totalForUser(userId));
    }

    @GetMapping("/leaderboard")
    @Operation(summary = "Top cashback earners this month (Redis sorted set)")
    public LeaderboardResponse leaderboard(@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        YearMonth month = leaderboard.currentMonth();
        return new LeaderboardResponse(month, leaderboard.top(month, limit));
    }
}
