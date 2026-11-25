package io.nikhil.cqrsbankaccount.core.exceptions

// Expected outcomes the client can act on. Anything else thrown while handling a request is a defect
// and is reported as a 500 without its message.
sealed class DomainException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidCommandException(message: String) : DomainException(message)

class AccountNotFoundException(accountId: String) : DomainException("Account $accountId was not found.")

class AccountClosedException : DomainException("The account is closed.")

class InsufficientFundsException : DomainException("Insufficient funds.")

class NonZeroBalanceException : DomainException("An account must have a zero balance before it can be closed.")

class ConcurrencyConflictException(
    accountId: String,
    cause: Throwable? = null,
) : DomainException("Account $accountId was changed by another request; retry the command.", cause)

class IdempotencyKeyReusedException(cause: Throwable? = null) :
    DomainException("Idempotency key was already used for a different command.", cause)
