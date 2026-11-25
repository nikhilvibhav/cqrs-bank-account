package io.nikhil.cqrsbankaccount.command.domain

import io.nikhil.cqrsbankaccount.common.dtos.AccountType
import io.nikhil.cqrsbankaccount.common.events.AccountClosedEvent
import io.nikhil.cqrsbankaccount.common.events.AccountEvent
import io.nikhil.cqrsbankaccount.common.events.AccountOpenedEvent
import io.nikhil.cqrsbankaccount.common.events.FundsDepositedEvent
import io.nikhil.cqrsbankaccount.common.events.FundsWithdrawnEvent
import io.nikhil.cqrsbankaccount.core.events.BaseEvent
import io.nikhil.cqrsbankaccount.core.exceptions.AccountClosedException
import io.nikhil.cqrsbankaccount.core.exceptions.InsufficientFundsException
import io.nikhil.cqrsbankaccount.core.exceptions.InvalidCommandException
import io.nikhil.cqrsbankaccount.core.exceptions.NonZeroBalanceException
import java.math.BigDecimal
import java.time.Instant

class AccountAggregate private constructor(private val accountId: String) {
    var version: Int = -1
        private set
    var balance: BigDecimal = BigDecimal.ZERO
        private set
    var isOpen: Boolean = false
        private set

    private val pendingEvents = mutableListOf<AccountEvent>()
    val uncommittedEvents: List<AccountEvent>
        get() = pendingEvents.toList()

    fun deposit(amount: BigDecimal) {
        requirePositive(amount)
        requireOpen()
        record(FundsDepositedEvent(accountId, amount))
    }

    fun withdraw(amount: BigDecimal) {
        requirePositive(amount)
        requireOpen()
        if (balance < amount) throw InsufficientFundsException()
        record(FundsWithdrawnEvent(accountId, amount))
    }

    fun close() {
        requireOpen()
        if (balance.compareTo(BigDecimal.ZERO) != 0) throw NonZeroBalanceException()
        record(AccountClosedEvent(accountId))
    }

    private fun requireOpen() {
        if (!isOpen) throw AccountClosedException()
    }

    private fun record(event: AccountEvent) {
        event.version = version + 1
        apply(event)
        pendingEvents += event
    }

    // Events are facts that already happened, so replaying them must not re-check business rules: a rule
    // that changes later would otherwise make existing accounts impossible to load. Rules are enforced
    // in the command methods above; only the stream's own integrity is checked here.
    private fun apply(event: AccountEvent) {
        check(event.aggregateId == accountId) { "Event belongs to a different account." }
        check(event.version == version + 1) { "Account event version is out of sequence." }
        check((event is AccountOpenedEvent) == (version == -1)) {
            "An account's event stream must start with, and only contain one, opening event."
        }

        when (event) {
            is AccountOpenedEvent -> {
                balance = event.openingBalance
                isOpen = true
            }

            is FundsDepositedEvent -> balance += event.amount

            is FundsWithdrawnEvent -> balance -= event.amount

            is AccountClosedEvent -> isOpen = false
        }

        version = event.version
    }

    companion object {
        fun open(
            accountId: String,
            accountHolder: String,
            accountType: AccountType,
            openingBalance: BigDecimal,
            openedAt: Instant,
        ): AccountAggregate {
            if (accountId.isBlank()) throw InvalidCommandException("Account ID is required.")
            if (accountHolder.isBlank()) throw InvalidCommandException("Account holder is required.")
            if (openingBalance < BigDecimal.ZERO) throw InvalidCommandException("Opening balance cannot be negative.")
            return AccountAggregate(accountId).apply {
                record(AccountOpenedEvent(accountHolder.trim(), accountType, openingBalance, openedAt, accountId))
            }
        }

        fun rehydrate(accountId: String, history: List<BaseEvent>): AccountAggregate {
            check(history.isNotEmpty()) { "No events found for account $accountId." }
            return AccountAggregate(accountId).apply {
                history.forEach { event ->
                    check(event is AccountEvent) { "Unsupported account event: ${event.javaClass.simpleName}" }
                    apply(event)
                }
            }
        }

        private fun requirePositive(amount: BigDecimal) {
            if (amount <= BigDecimal.ZERO) throw InvalidCommandException("Amount must be greater than zero.")
        }
    }
}
