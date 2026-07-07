package io.flowspeech.springai.autoconfigure;

import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

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
	FlowSpeechApi flowSpeechApi(FlowSpeechConnectionProperties connectionProperties,
			ObjectProvider<RestClient.Builder> restClientBuilderProvider,
			ObjectProvider<WebClient.Builder> webClientBuilderProvider,
			ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
		return FlowSpeechApi.builder()
			.baseUrl(connectionProperties.getBaseUrl())
			.textToSpeechPath(connectionProperties.getTextToSpeechPath())
			.apiKey(connectionProperties.getApiKey())
			.restClientBuilder(restClientBuilderProvider.getIfAvailable(RestClient::builder))
			.webClientBuilder(webClientBuilderProvider.getIfAvailable(WebClient::builder))
			.responseErrorHandler(responseErrorHandler.getIfAvailable(() -> RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER))
			.build();
	}

	@Bean
	@ConditionalOnMissingBean
	FlowSpeechTextToSpeechModel flowSpeechTextToSpeechModel(FlowSpeechApi flowSpeechApi,
			FlowSpeechSpeechProperties speechProperties, ObjectProvider<RetryTemplate> retryTemplate) {
		return FlowSpeechTextToSpeechModel.builder()
			.flowSpeechApi(flowSpeechApi)
			.options(speechProperties.toOptions())
			.retryTemplate(retryTemplate.getIfUnique(() -> RetryUtils.DEFAULT_RETRY_TEMPLATE))
			.build();
	}

}
