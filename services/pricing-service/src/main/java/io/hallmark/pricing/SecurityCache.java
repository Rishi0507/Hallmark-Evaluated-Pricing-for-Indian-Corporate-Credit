package io.hallmark.pricing;

import io.hallmark.pricing.domain.SecurityView;
import io.hallmark.pricing.persistence.PricingRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SecurityCache {
    private final Map<String, SecurityView> byIsin = new ConcurrentHashMap<>();
    private final PricingRepository repository;
    private final JsonMapper mapper;

    public SecurityCache(PricingRepository repository, JsonMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        repository.eligibleSecurityJson().forEach(json -> put(mapper.readValue(json, SecurityView.class)));
    }

    public void put(SecurityView view) {
        if (view.pricingEligible()) {
            byIsin.put(view.isin(), view);
        } else {
            byIsin.remove(view.isin());
        }
    }

    public Map<String, SecurityView> snapshot() {
        return Map.copyOf(byIsin);
    }

    public int size() {
        return byIsin.size();
    }
}
