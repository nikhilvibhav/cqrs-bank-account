package io.nikhil.cqrsbankaccount.command.api.commands

import io.nikhil.cqrsbankaccount.command.domain.AccountAggregate
import io.nikhil.cqrsbankaccount.core.commands.CommandResult
import io.nikhil.cqrsbankaccount.core.events.BaseEvent
import io.nikhil.cqrsbankaccount.core.exceptions.AccountNotFoundException
import io.nikhil.cqrsbankaccount.core.exceptions.ConcurrencyConflictException
import io.nikhil.cqrsbankaccount.core.infra.EventStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Handles every [AccountCommand] sent on the `accountCommandChannel`.
 *
 * A command is handled by loading the account's events, replaying them into an [AccountAggregate], letting
 * the aggregate decide which new events to record, and appending those to the [EventStore] together with a
 * receipt for the command. Each command is identified by its idempotency key, or by a random id when the
 * client sent none, and by a fingerprint of its content, so that a retried command is applied only once.
 *
 * @param eventStore where account events and command receipts are stored.
 * @param clock the source of domain time, such as the time an account is opened.
 * @param maxAttempts how many times a command is attempted when it conflicts with a concurrent change to
 *   the same account (`app.commands.max-attempts`); must be at least 1.
 */
@Component
class AccountCommandHandler(
    private val eventStore: EventStore,
    private val clock: Clock,
    @Value("\${app.commands.max-attempts:5}") private val maxAttempts: Int,
) {
    init {
        require(maxAttempts >= 1) { "app.commands.max-attempts must be at least 1." }
    }

    /**
     * Applies [command] and returns the account it changed with the account's new event version.
     *
     * A [ConcurrencyConflictException] means another command changed the account (or used the same
     * idempotency key) after this one read it. Each attempt then starts over: a retry of the same key finds
     * the other request's receipt and returns its result, and any other command is re-validated against
     * fresh state. The exception is rethrown once [maxAttempts] attempts have conflicted.
     *
     * @throws io.nikhil.cqrsbankaccount.core.exceptions.DomainException when the command is rejected.
     */
    @ServiceActivator(inputChannel = "accountCommandChannel")
    fun handle(command: AccountCommand): CommandResult {
        val commandId = command.idempotencyKey?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        val fingerprint = fingerprint(command)

        var attempt = 1
        while (true) {
            try {
                eventStore.findCommandResult(commandId, fingerprint)?.let { return it }
                return execute(command, commandId, fingerprint)
            } catch (exception: ConcurrencyConflictException) {
                if (attempt >= maxAttempts) throw exception
                backOff(attempt++)
            }
        }
    }

    /** Runs one attempt of [command], dispatching on its type. */
    private fun execute(command: AccountCommand, commandId: String, fingerprint: String): CommandResult =
        when (command) {
            is OpenAccountCommand -> openAccount(command, commandId, fingerprint)

            is DepositFundsCommand -> updateAccount(command.accountId, commandId, fingerprint) {
                it.deposit(command.amount)
            }

            is WithdrawFundsCommand -> updateAccount(command.accountId, commandId, fingerprint) {
                it.withdraw(command.amount)
            }

            is CloseAccountCommand -> updateAccount(command.accountId, commandId, fingerprint) { it.close() }
        }

    /** Records the opening event of a new account, which must not have any events yet. */
    private fun openAccount(command: OpenAccountCommand, commandId: String, fingerprint: String): CommandResult {
        val aggregate = AccountAggregate.open(
            command.accountId,
            command.accountHolder,
            command.accountType,
            command.openingBalance,
            clock.instant(),
        )
        return persist(command.accountId, aggregate.uncommittedEvents, -1, commandId, fingerprint)
    }

    /**
     * Rehydrates an existing account from its events, applies [update] to it and stores the events that
     * [update] recorded, expecting the account to still be at the version that was read.
     *
     * @throws AccountNotFoundException when the account has no events.
     */
    private fun updateAccount(
        accountId: String,
        commandId: String,
        fingerprint: String,
        update: (AccountAggregate) -> Unit,
    ): CommandResult {
        val history = eventStore.getEvents(accountId)
        if (history.isEmpty()) throw AccountNotFoundException(accountId)

        val aggregate = AccountAggregate.rehydrate(accountId, history)
        val expectedVersion = aggregate.version
        update(aggregate)
        return persist(accountId, aggregate.uncommittedEvents, expectedVersion, commandId, fingerprint)
    }

    private fun persist(
        accountId: String,
        events: List<BaseEvent>,
        expectedVersion: Int,
        commandId: String,
        fingerprint: String,
    ): CommandResult = eventStore.saveEvents(accountId, events, expectedVersion, commandId, fingerprint)

    /**
     * Returns a SHA-256 hash of what [command] does, used to tell a genuine retry of an idempotency key
     * from a different command that reuses it.
     *
     * Amounts are normalised so that, for example, `10` and `10.00` match. An open command's fingerprint
     * leaves out the account id, because the server generates a new one for every request.
     */
    private fun fingerprint(command: AccountCommand): String {
        val payload = when (command) {
            is OpenAccountCommand -> listOf(
                "open",
                command.accountHolder.trim(),
                command.accountType.name,
                command.openingBalance.stripTrailingZeros().toPlainString(),
            ).joinToString("\u0000")

            is DepositFundsCommand -> listOf(
                "deposit",
                command.accountId,
                command.amount.stripTrailingZeros().toPlainString(),
            ).joinToString("\u0000")

            is WithdrawFundsCommand -> listOf(
                "withdraw",
                command.accountId,
                command.amount.stripTrailingZeros().toPlainString(),
            ).joinToString("\u0000")

            is CloseAccountCommand -> "close\u0000${command.accountId}"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
    }

    /**
     * Waits a random time of up to [BACK_OFF_STEP_MS] times [attempt] milliseconds, so competing requests
     * stop colliding on the same retry schedule.
     */
    private fun backOff(attempt: Int) {
        Thread.sleep(ThreadLocalRandom.current().nextLong(1, BACK_OFF_STEP_MS * attempt + 1))
    }

    private companion object {
        const val BACK_OFF_STEP_MS = 20L
    }
}
