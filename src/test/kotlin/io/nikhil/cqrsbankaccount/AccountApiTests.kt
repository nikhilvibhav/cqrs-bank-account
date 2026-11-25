package io.nikhil.cqrsbankaccount

import io.nikhil.cqrsbankaccount.common.events.AccountOpenedEvent
import io.nikhil.cqrsbankaccount.core.events.CommandReceipt
import io.nikhil.cqrsbankaccount.core.events.EventModel
import io.nikhil.cqrsbankaccount.query.api.models.AccountView
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.fail

/** Exercises the HTTP API through the application's full MVC configuration, MongoDB and the projection. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["app.projection.enabled=true", "app.projection.retry-interval=200ms"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountApiTests : MongoIntegrationTest() {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        listOf(EventModel::class, CommandReceipt::class, AccountView::class)
            .forEach { mongoTemplate.remove(Query(), it.java) }
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
    }

    @Test
    fun `an account's life cycle returns its location and event versions`() {
        val opened = openAccount("Ada Lovelace", "10.00")
        val accountId = idOf("Ada Lovelace")
        opened
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "http://localhost/api/v1/accounts/$accountId"))
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.accountId").value(accountId))
            .andExpect(jsonPath("$.version").value(0))

        postJson("/api/v1/accounts/$accountId/deposits", """{"amount":5}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
        postJson("/api/v1/accounts/$accountId/withdrawals", """{"amount":15}""")
            .andExpect(jsonPath("$.version").value(2))
        mockMvc.perform(post("/api/v1/accounts/$accountId/closure"))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"3\""))

        // The read model reaches the version the last write returned.
        await("the projection") {
            mockMvc.perform(get("/api/v1/accounts/$accountId")).andReturn().response.getHeader("ETag") == "\"3\""
        }
        mockMvc.perform(get("/api/v1/accounts/$accountId"))
            .andExpect(jsonPath("$.closed").value(true))
            .andExpect(jsonPath("$.balance").value(0))
    }

    @Test
    fun `a replayed open returns the original account`() {
        val first = openAccount("Ada Lovelace", "10", idempotencyKey = "open-1").andReturn().response
        val replay = openAccount("Ada Lovelace", "10", idempotencyKey = "open-1").andReturn().response

        assertEquals(first.getHeader("Location"), replay.getHeader("Location"))
        assertEquals(first.contentAsString, replay.contentAsString)
    }

    @Test
    fun `accounts are listed a page at a time and can be filtered by status`() {
        listOf("Ada", "Grace", "Alan").forEach { openAccount(it, "0") }
        mockMvc.perform(post("/api/v1/accounts/${idOf("Alan")}/closure")).andExpect(status().isOk)
        await("the projection") { mongoTemplate.count(Query(), AccountView::class.java) == 3L }
        await("the closure") { mongoTemplate.findById(idOf("Alan"), AccountView::class.java)?.closed == true }

        mockMvc.perform(get("/api/v1/accounts").param("size", "2"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.totalItems").value(3))
            .andExpect(jsonPath("$.items[0].accountHolder").value("Ada"))
        mockMvc.perform(get("/api/v1/accounts").param("size", "2").param("page", "1"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].accountHolder").value("Alan"))
        mockMvc.perform(get("/api/v1/accounts").param("closed", "false"))
            .andExpect(jsonPath("$.totalItems").value(2))
    }

    @Test
    fun `a page size above the maximum is rejected`() {
        mockMvc.perform(get("/api/v1/accounts").param("size", "101"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("size"))
    }

    @Test
    fun `the old verb-style paths are gone`() {
        postJson("/api/v1/open-bank-account", """{"accountHolder":"Ada","accountType":"SAVINGS"}""")
            .andExpect(status().isNotFound)
        mockMvc.perform(delete("/api/v1/close-bank-account/some-id")).andExpect(status().isNotFound)
    }

    private fun openAccount(holder: String, openingBalance: String, idempotencyKey: String? = null): ResultActions {
        val request = post("/api/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"accountHolder":"$holder","accountType":"SAVINGS","openingBalance":$openingBalance}""")
        idempotencyKey?.let { request.header("Idempotency-Key", it) }
        return mockMvc.perform(request)
    }

    private fun postJson(path: String, body: String) =
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))

    /** Account ids are generated by the server, so look them up by holder in the event store. */
    private fun idOf(holder: String): String =
        mongoTemplate.findAll(EventModel::class.java)
            .single { (it.eventData as? AccountOpenedEvent)?.accountHolder == holder }
            .aggregateIdentifier

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = Instant.now() + Duration.ofSeconds(15)
        while (Instant.now().isBefore(deadline)) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for $description.")
    }
}
