package com.huashuo.task.job;

import com.huashuo.task.service.TaskResultAssetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@ConditionalOnProperty(prefix = "huashuo.asset.generated-result-backfill", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class GeneratedResultAssetBackfillRunner implements ApplicationRunner {

    private final TaskResultAssetService taskResultAssetService;

    @Value("${huashuo.asset.generated-result-backfill.max-items:200}")
    private int maxItems;

    @Value("${huashuo.asset.generated-result-backfill.lookback-hours:168}")
    private int lookbackHours;

    @Override
    public void run(ApplicationArguments args) {
        int count = taskResultAssetService.backfillRecentSuccessTasks(maxItems, lookbackHours);
        if (count > 0) {
            log.info("Backfilled {} generated result asset(s) for successful task(s).", count);
        }
    }
}
