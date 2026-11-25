package io.nikhil.cqrsbankaccount.core.infra

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    // Injected wherever domain time is needed, so tests can use a fixed clock.
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
