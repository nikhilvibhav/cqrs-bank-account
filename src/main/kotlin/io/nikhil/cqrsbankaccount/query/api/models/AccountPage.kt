package io.nikhil.cqrsbankaccount.query.api.models

/**
 * One page of accounts.
 *
 * @property items the accounts on this page.
 * @property page the zero-based page number.
 * @property size the requested page size; [items] is smaller on the last page.
 * @property totalItems the number of matching accounts across all pages.
 */
data class AccountPage(
    val items: List<AccountView>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
)
