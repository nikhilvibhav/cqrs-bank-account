package io.nikhil.cqrsbankaccount

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.integration.channel.DirectChannel
import kotlin.test.assertEquals

@SpringBootTest(properties = ["app.projection.enabled=false"])
class IntegrationConfigurationTests {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `command and event channels each have a service activator`() {
        val commandChannel = applicationContext.getBean<DirectChannel>("accountCommandChannel")
        val eventChannel = applicationContext.getBean<DirectChannel>("accountEventChannel")

        assertEquals(1, commandChannel.subscriberCount)
        assertEquals(1, eventChannel.subscriberCount)
        applicationContext.getBean<MongoTransactionManager>()
    }
}
