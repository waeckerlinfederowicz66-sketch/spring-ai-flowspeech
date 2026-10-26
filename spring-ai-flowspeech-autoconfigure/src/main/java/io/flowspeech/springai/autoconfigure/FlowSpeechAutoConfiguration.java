package io.flowspeech.springai.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.flowspeech.springai.FlowSpeechTextToSpeechModel;
import io.flowspeech.springai.api.FlowSpeechApi;

@AutoConfiguration
@ConditionalOnClass(FlowSpeechApi.class)
@EnableConfigurationProperties({ FlowSpeechConnectionProperties.class, FlowSpeechSpeechProperties.class })
@ConditionalOnProperty(prefix = FlowSpeechConnectionProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class FlowSpeechAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    FlowSpeechApi flowSpeechApi(FlowSpeechConnectionProperties properties) {
        return FlowSpeechApi.builder().baseUrl(properties.getBaseUrl()).apiKey(properties.getApiKey())
                .textToSpeechPath(properties.getTextToSpeechPath()).timeout(properties.getTimeout())
                .requestTimeout(properties.getRequestTimeout()).pollInterval(properties.getPollInterval())
                .maxRetries(properties.getMaxRetries()).maxDownloadBytes(properties.getMaxDownloadBytes()).build();
    }

    @Bean
    @ConditionalOnMissingBean
    FlowSpeechTextToSpeechModel flowSpeechTextToSpeechModel(FlowSpeechApi api, FlowSpeechSpeechProperties properties) {
        return new FlowSpeechTextToSpeechModel(api, properties.toOptions());
    }
}
