package io.nikhil.cqrsbankaccount.core.exceptions

/**
 * An expected outcome the client can act on. Anything else thrown while handling a request is a defect
 * and is reported as a 500 without its message.
 *
 * Being sealed, the API's mapping of these exceptions to HTTP statuses is checked for exhaustiveness.
 */
sealed class DomainException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The command is malformed, such as a blank account holder or a non-positive amount. */
class InvalidCommandException(message: String) : DomainException(message)

/** No account has the given id. */
class AccountNotFoundException(accountId: String) : DomainException("Account $accountId was not found.")

/** The account is closed and accepts no further changes. */
class AccountClosedException : DomainException("The account is closed.")

/** A withdrawal is larger than the balance. */
class InsufficientFundsException : DomainException("Insufficient funds.")

/** An account cannot be closed while it holds funds. */
class NonZeroBalanceException : DomainException("An account must have a zero balance before it can be closed.")

/**
 * Another request changed the account between this command reading and writing it. The command handler
 * retries it; the client sees it only once every attempt has conflicted.
 */
class ConcurrencyConflictException(
    accountId: String,
    cause: Throwable? = null,
) : DomainException("Account $accountId was changed by another request; retry the command.", cause)

/** The idempotency key was already used for a command with different content. */
class IdempotencyKeyReusedException(cause: Throwable? = null) :
    DomainException("Idempotency key was already used for a different command.", cause)
