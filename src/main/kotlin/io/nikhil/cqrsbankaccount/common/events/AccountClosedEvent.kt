package io.nikhil.cqrsbankaccount.common.events

import org.springframework.data.annotation.TypeAlias

/**
 * Recorded when an account is closed. The account stays readable but accepts no further commands.
 *
 * @property accountId the account that was closed.
 */
@TypeAlias("AccountClosed")
data class AccountClosedEvent(val accountId: String) : AccountEvent(accountId)
