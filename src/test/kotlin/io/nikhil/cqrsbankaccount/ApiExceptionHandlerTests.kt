package io.nikhil.cqrsbankaccount

import io.nikhil.cqrsbankaccount.command.api.controllers.AccountCommandController
import io.nikhil.cqrsbankaccount.common.dtos.ApiExceptionHandler
import io.nikhil.cqrsbankaccount.core.exceptions.AccountClosedException
import io.nikhil.cqrsbankaccount.core.exceptions.AccountNotFoundException
import io.nikhil.cqrsbankaccount.core.exceptions.ConcurrencyConflictException
import io.nikhil.cqrsbankaccount.core.exceptions.IdempotencyKeyReusedException
import io.nikhil.cqrsbankaccount.core.exceptions.InsufficientFundsException
import io.nikhil.cqrsbankaccount.core.exceptions.InvalidCommandException
import io.nikhil.cqrsbankaccount.core.exceptions.NonZeroBalanceException
import io.nikhil.cqrsbankaccount.core.infra.SpringIntegrationCommandDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.integration.channel.DirectChannel
import org.springframework.integration.handler.AbstractMessageHandler
import org.springframework.messaging.Message
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ApiExceptionHandlerTests {
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("domainExceptions")
    fun `domain exceptions map to problem details`(exception: RuntimeException, status: Int, title: String) {
        mockMvcThrowing(exception)
            .perform(deposit())
            .andExpect(status().`is`(status))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value(title))
            .andExpect(jsonPath("$.detail").value(exception.message))
    }

    @Test
    fun `unexpected exceptions return 500 without leaking their message`() {
        mockMvcThrowing(IllegalStateException("Account event version is out of sequence."))
            .perform(deposit())
            .andExpect(status().isInternalServerError)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
    }

    @Test
    fun `malformed request bodies return a 400 problem`() {
        mockMvcThrowing(IllegalStateException("not reached"))
            .perform(
                post("/api/v1/accounts/account-1/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":"),
            )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(strings = ["0", "-1", "0.001", "10000000000000"])
    fun `invalid amounts are rejected with a field error before reaching the handler`(amount: String) {
        mockMvcThrowing(IllegalStateException("not reached"))
            .perform(
                post("/api/v1/accounts/account-1/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":$amount}"),
            )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errors[0].field").value("amount"))
    }

    @Test
    fun `an account holder must not be blank`() {
        mockMvcThrowing(IllegalStateException("not reached"))
            .perform(
                post("/api/v1/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountHolder\":\" \",\"accountType\":\"SAVINGS\"}"),
            )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("accountHolder"))
    }

    private fun deposit() =
        post("/api/v1/accounts/account-1/deposits")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":10}")

    // Wires the real dispatcher to a Spring Integration message handler, as in production, so the test
    // also covers unwrapping the exception from the MessageHandlingException the handler throws.
    private fun mockMvcThrowing(exception: RuntimeException): MockMvc {
        val channel = DirectChannel()
        channel.subscribe(
            object : AbstractMessageHandler() {
                override fun handleMessageInternal(message: Message<*>): Unit = throw exception
            },
        )
        return MockMvcBuilders
            .standaloneSetup(AccountCommandController(SpringIntegrationCommandDispatcher(channel)))
            .setControllerAdvice(ApiExceptionHandler())
            .build()
    }

    companion object {
        @JvmStatic
        fun domainExceptions() =
            listOf(
                Arguments.of(InvalidCommandException("Amount must be greater than zero."), 400, "Invalid command"),
                Arguments.of(AccountNotFoundException("account-1"), 404, "Account not found"),
                Arguments.of(AccountClosedException(), 422, "Account closed"),
                Arguments.of(InsufficientFundsException(), 422, "Insufficient funds"),
                Arguments.of(NonZeroBalanceException(), 422, "Non-zero balance"),
                Arguments.of(IdempotencyKeyReusedException(), 422, "Idempotency key reused"),
                Arguments.of(ConcurrencyConflictException("account-1"), 409, "Concurrent modification"),
            )
    }
}
