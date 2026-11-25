package io.nikhil.cqrsbankaccount

import io.nikhil.cqrsbankaccount.command.api.commands.AccountCommandHandler
import io.nikhil.cqrsbankaccount.command.api.commands.OpenAccountCommand
import io.nikhil.cqrsbankaccount.common.dtos.AccountType
import io.nikhil.cqrsbankaccount.common.events.AccountOpenedEvent
import io.nikhil.cqrsbankaccount.core.commands.CommandResult
import io.nikhil.cqrsbankaccount.core.events.BaseEvent
import io.nikhil.cqrsbankaccount.core.exceptions.ConcurrencyConflictException
import io.nikhil.cqrsbankaccount.core.infra.EventStore
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountCommandHandlerTests {
    private val eventStore = Mockito.mock(EventStore::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)

    @Test
    fun `retry of an open command returns its original account id`() {
        `when`(eventStore.findCommandResult(anyString(), anyString())).thenReturn(CommandResult("original-account", 0))

        val result = AccountCommandHandler(eventStore, clock, maxAttempts = 3).handle(openCommand())

        assertEquals(CommandResult("original-account", 0), result)
        verify(eventStore, never()).saveEvents(anyString(), anyEvents(), Mockito.anyInt(), anyString(), anyString())
    }

    @Test
    fun `a concurrency conflict is retried from a fresh read`() {
        `when`(eventStore.saveEvents(anyString(), anyEvents(), Mockito.anyInt(), anyString(), anyString()))
            .thenThrow(ConcurrencyConflictException("new-account"))
            .thenReturn(CommandResult("new-account", 0))

        val result = AccountCommandHandler(eventStore, clock, maxAttempts = 3).handle(openCommand())

        assertEquals(CommandResult("new-account", 0), result)
        verify(eventStore, times(2)).findCommandResult(anyString(), anyString())
        verify(eventStore, times(2)).saveEvents(anyString(), anyEvents(), Mockito.anyInt(), anyString(), anyString())
    }

    @Test
    fun `a conflict that persists is reported after the last attempt`() {
        `when`(eventStore.saveEvents(anyString(), anyEvents(), Mockito.anyInt(), anyString(), anyString()))
            .thenThrow(ConcurrencyConflictException("new-account"))

        assertFailsWith<ConcurrencyConflictException> {
            AccountCommandHandler(eventStore, clock, maxAttempts = 3).handle(openCommand())
        }

        verify(eventStore, times(3)).saveEvents(anyString(), anyEvents(), Mockito.anyInt(), anyString(), anyString())
    }

    @Test
    fun `an opened account records the time from the injected clock`() {
        var savedEvents = emptyList<BaseEvent>()
        `when`(eventStore.saveEvents(anyString(), anyEvents(), Mockito.anyInt(), anyString(), anyString()))
            .thenAnswer { invocation ->
                savedEvents = invocation.getArgument(1)
                CommandResult("new-account", 0)
            }

        AccountCommandHandler(eventStore, clock, maxAttempts = 3).handle(openCommand())

        assertEquals(clock.instant(), (savedEvents.single() as AccountOpenedEvent).createdAt)
    }

    private fun openCommand() =
        OpenAccountCommand(
            accountId = "new-account",
            accountHolder = "Ada Lovelace",
            accountType = AccountType.SAVINGS,
            openingBalance = BigDecimal("25.00"),
            idempotencyKey = "request-123",
        )

    private fun anyEvents(): List<BaseEvent> = Mockito.anyList()
}
