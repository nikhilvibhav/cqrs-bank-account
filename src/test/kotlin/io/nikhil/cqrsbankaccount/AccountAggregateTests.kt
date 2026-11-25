package io.nikhil.cqrsbankaccount

import io.nikhil.cqrsbankaccount.command.domain.AccountAggregate
import io.nikhil.cqrsbankaccount.common.dtos.AccountType
import io.nikhil.cqrsbankaccount.common.events.AccountOpenedEvent
import io.nikhil.cqrsbankaccount.common.events.FundsDepositedEvent
import io.nikhil.cqrsbankaccount.common.events.FundsWithdrawnEvent
import io.nikhil.cqrsbankaccount.core.exceptions.AccountClosedException
import io.nikhil.cqrsbankaccount.core.exceptions.InsufficientFundsException
import io.nikhil.cqrsbankaccount.core.exceptions.InvalidCommandException
import io.nikhil.cqrsbankaccount.core.exceptions.NonZeroBalanceException
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests of the account's business rules and of replaying its event history. */
class AccountAggregateTests {
    @Test
    fun `deposit and withdrawal update balance and can be replayed`() {
        val account = AccountAggregate.open(
            "account-1",
            "Ada Lovelace",
            AccountType.SAVINGS,
            BigDecimal("25.00"),
            Instant.EPOCH,
        )
        account.deposit(BigDecimal("10.50"))
        account.withdraw(BigDecimal("5.25"))

        assertEquals(BigDecimal("30.25"), account.balance)

        val restored = AccountAggregate.rehydrate("account-1", account.uncommittedEvents)
        assertEquals(BigDecimal("30.25"), restored.balance)
        assertTrue(restored.isOpen)
    }

    @Test
    fun `withdrawal cannot overdraw account`() {
        val account = AccountAggregate.open(
            "account-1",
            "Ada Lovelace",
            AccountType.SAVINGS,
            BigDecimal("10"),
            Instant.EPOCH,
        )

        assertFailsWith<InsufficientFundsException> { account.withdraw(BigDecimal("10.01")) }
        assertEquals(BigDecimal("10"), account.balance)
    }

    @Test
    fun `amounts must be positive`() {
        val account = AccountAggregate.open(
            "account-1",
            "Ada Lovelace",
            AccountType.CURRENT,
            BigDecimal.ZERO,
            Instant.EPOCH,
        )

        assertFailsWith<InvalidCommandException> { account.deposit(BigDecimal.ZERO) }
        assertFailsWith<InvalidCommandException> { account.withdraw(BigDecimal("-1")) }
    }

    @Test
    fun `account can only close with zero balance`() {
        val account = AccountAggregate.open(
            "account-1",
            "Ada Lovelace",
            AccountType.CURRENT,
            BigDecimal("1"),
            Instant.EPOCH,
        )

        assertFailsWith<NonZeroBalanceException> { account.close() }
        account.withdraw(BigDecimal("1"))
        account.close()

        assertFalse(account.isOpen)
        assertFailsWith<AccountClosedException> { account.deposit(BigDecimal.ONE) }
    }

    @Test
    fun `history is replayed even when it breaks rules that apply to new commands`() {
        // For example, an overdraft that an earlier version of the rules allowed.
        val history = listOf(
            AccountOpenedEvent("Ada Lovelace", AccountType.CURRENT, BigDecimal("10"), Instant.EPOCH, "account-1")
                .apply { version = 0 },
            FundsWithdrawnEvent("account-1", BigDecimal("25")).apply { version = 1 },
        )

        val account = AccountAggregate.rehydrate("account-1", history)

        assertEquals(BigDecimal("-15"), account.balance)
        assertEquals(1, account.version)
    }

    @Test
    fun `history must start with exactly one opening event`() {
        val deposit = FundsDepositedEvent("account-1", BigDecimal("5")).apply { version = 0 }
        assertFailsWith<IllegalStateException> { AccountAggregate.rehydrate("account-1", listOf(deposit)) }

        val opened = AccountAggregate.open(
            "account-1",
            "Ada Lovelace",
            AccountType.SAVINGS,
            BigDecimal.ZERO,
            Instant.EPOCH,
        )
        val reopened = AccountOpenedEvent(
            "Ada Lovelace",
            AccountType.SAVINGS,
            BigDecimal.ZERO,
            Instant.EPOCH,
            "account-1",
        )
            .apply { version = 1 }
        assertFailsWith<IllegalStateException> {
            AccountAggregate.rehydrate("account-1", opened.uncommittedEvents + reopened)
        }
    }
}
