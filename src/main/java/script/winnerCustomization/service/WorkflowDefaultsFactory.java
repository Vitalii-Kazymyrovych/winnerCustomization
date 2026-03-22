package script.winnerCustomization.service;

import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;

@Component
public class WorkflowDefaultsFactory {
    public AppConfig enrich(AppConfig config) {
        return config;
    }
}
