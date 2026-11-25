package io.nikhil.cqrsbankaccount.query.api.models

data class AccountPage(
    val items: List<AccountView>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
)
