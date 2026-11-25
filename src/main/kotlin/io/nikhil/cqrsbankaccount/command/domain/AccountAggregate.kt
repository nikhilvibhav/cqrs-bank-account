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

/**
 * The write model of a bank account, and the only place its business rules are enforced.
 *
 * An aggregate is either created by [open] or rebuilt from its stored events by [rehydrate]. Its command
 * methods check the rules against the current state and record the resulting events, which are applied to
 * the state immediately and collected in [uncommittedEvents] for the caller to store. An aggregate is
 * short-lived: it is built for one command and then discarded.
 */
class AccountAggregate private constructor(private val accountId: String) {
    /** The version of the latest event applied, or -1 before the opening event. */
    var version: Int = -1
        private set

    /** The current balance. */
    var balance: BigDecimal = BigDecimal.ZERO
        private set

    /** Whether the account has been opened and not yet closed. */
    var isOpen: Boolean = false
        private set

    private val pendingEvents = mutableListOf<AccountEvent>()

    /** The events recorded since the aggregate was created or rehydrated, oldest first. */
    val uncommittedEvents: List<AccountEvent>
        get() = pendingEvents.toList()

    /**
     * Deposits [amount] into the account.
     *
     * @throws InvalidCommandException when [amount] is not positive.
     * @throws AccountClosedException when the account is closed.
     */
    fun deposit(amount: BigDecimal) {
        requirePositive(amount)
        requireOpen()
        record(FundsDepositedEvent(accountId, amount))
    }

    /**
     * Withdraws [amount] from the account.
     *
     * @throws InvalidCommandException when [amount] is not positive.
     * @throws AccountClosedException when the account is closed.
     * @throws InsufficientFundsException when [amount] is more than the balance.
     */
    fun withdraw(amount: BigDecimal) {
        requirePositive(amount)
        requireOpen()
        if (balance < amount) throw InsufficientFundsException()
        record(FundsWithdrawnEvent(accountId, amount))
    }

    /**
     * Closes the account.
     *
     * @throws AccountClosedException when the account is already closed.
     * @throws NonZeroBalanceException when the balance is not zero.
     */
    fun close() {
        requireOpen()
        if (balance.compareTo(BigDecimal.ZERO) != 0) throw NonZeroBalanceException()
        record(AccountClosedEvent(accountId))
    }

    private fun requireOpen() {
        if (!isOpen) throw AccountClosedException()
    }

    /** Assigns [event] the next version, applies it and keeps it as uncommitted. */
    private fun record(event: AccountEvent) {
        event.version = version + 1
        apply(event)
        pendingEvents += event
    }

    /**
     * Updates the state from [event].
     *
     * Events are facts that already happened, so replaying them must not re-check business rules: a rule
     * that changes later would otherwise make existing accounts impossible to load. Rules are enforced in
     * the command methods above; only the stream's own integrity is checked here.
     *
     * @throws IllegalStateException when [event] belongs to another account, is out of sequence, or is an
     *   opening event anywhere but at the start of the stream.
     */
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
        /**
         * Creates a new account and records its opening event.
         *
         * @param openedAt when the account was opened, as recorded in the event.
         * @throws InvalidCommandException when the id or holder is blank or the opening balance is negative.
         */
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

        /**
         * Rebuilds an account by replaying its stored [history], oldest first.
         *
         * @throws IllegalStateException when [history] is empty, contains an event that is not an
         *   [AccountEvent], or is not a valid stream for the account.
         */
        fun rehydrate(accountId: String, history: List<BaseEvent>): AccountAggregate {
            check(history.isNotEmpty()) { "No events found for account $accountId." }
            return AccountAggregate(accountId).apply {
                history.forEach { event ->
                    check(event is AccountEvent) { "Unsupported account event: ${event.javaClass.simpleName}" }
                    apply(event)
                }
            }
        }

        /** @throws InvalidCommandException when [amount] is zero or negative. */
        private fun requirePositive(amount: BigDecimal) {
            if (amount <= BigDecimal.ZERO) throw InvalidCommandException("Amount must be greater than zero.")
        }
    }
}
