package io.nikhil.cqrsbankaccount.core.infra

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.channel.DirectChannel
import org.springframework.integration.config.EnableIntegration
import org.springframework.messaging.MessageChannel

/**
 * The Spring Integration channels that connect the application's parts. Both are direct channels, so a
 * message is handled synchronously on the sender's thread.
 */
@Configuration(proxyBeanMethods = false)
@EnableIntegration
class IntegrationConfiguration {
    /** Carries commands from the [CommandDispatcher] to the account command handler. */
    @Bean
    fun accountCommandChannel(): MessageChannel = DirectChannel()

    /** Carries stored account events from the projection worker to the account projection. */
    @Bean
    fun accountEventChannel(): MessageChannel = DirectChannel()
}
