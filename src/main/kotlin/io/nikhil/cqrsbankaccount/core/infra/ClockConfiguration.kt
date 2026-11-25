package io.nikhil.cqrsbankaccount.core.infra

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Provides the application's [Clock]. */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    /** The UTC system clock, injected wherever domain time is needed so tests can use a fixed clock. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
