package io.nikhil.cqrsbankaccount.query.api.controllers

import io.nikhil.cqrsbankaccount.core.exceptions.AccountNotFoundException
import io.nikhil.cqrsbankaccount.query.api.models.AccountPage
import io.nikhil.cqrsbankaccount.query.api.models.AccountView
import io.nikhil.cqrsbankaccount.query.api.models.AccountViewRepository
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/accounts")
class AccountQueryController(private val accounts: AccountViewRepository) {
    @GetMapping
    fun listAccounts(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) size: Int,
        @RequestParam(required = false) closed: Boolean?,
    ): AccountPage {
        // The id breaks ties between accounts opened at the same instant, so pages never overlap.
        val pageable = PageRequest.of(page, size, Sort.by("createdAt", "id"))
        val result = closed?.let { accounts.findByClosed(it, pageable) } ?: accounts.findAll(pageable)
        return AccountPage(result.content, page, size, result.totalElements)
    }

    // The ETag is the read model's event version for the account; compare it with the version a write
    // returned to know whether that write is visible yet.
    @GetMapping("/{id}")
    fun getAccount(@PathVariable id: String): ResponseEntity<AccountView> {
        val account = accounts.findByIdOrNull(id) ?: throw AccountNotFoundException(id)
        return ResponseEntity.ok().eTag(account.version.toString()).body(account)
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100L
    }
}
