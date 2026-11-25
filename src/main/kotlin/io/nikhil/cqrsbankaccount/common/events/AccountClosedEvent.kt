package io.nikhil.cqrsbankaccount.common.events

import org.springframework.data.annotation.TypeAlias

@TypeAlias("AccountClosed")
data class AccountClosedEvent(val accountId: String) : AccountEvent(accountId)
