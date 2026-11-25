package io.nikhil.cqrsbankaccount.command.api.controllers

import io.nikhil.cqrsbankaccount.command.api.commands.AccountCommand
import io.nikhil.cqrsbankaccount.command.api.commands.CloseAccountCommand
import io.nikhil.cqrsbankaccount.command.api.commands.DepositFundsCommand
import io.nikhil.cqrsbankaccount.command.api.commands.OpenAccountCommand
import io.nikhil.cqrsbankaccount.command.api.commands.WithdrawFundsCommand
import io.nikhil.cqrsbankaccount.command.api.dtos.CommandResponse
import io.nikhil.cqrsbankaccount.command.api.dtos.FundsRequest
import io.nikhil.cqrsbankaccount.command.api.dtos.OpenAccountRequest
import io.nikhil.cqrsbankaccount.core.infra.CommandDispatcher
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/**
 * The write side of the accounts API.
 *
 * Each endpoint turns its request into an [AccountCommand] and sends it through the [CommandDispatcher].
 * Every endpoint accepts an optional `Idempotency-Key` header: a retry with the same key and the same
 * request returns the original result instead of applying the command again, and the same key with a
 * different request is rejected. A successful response's `ETag` is the account's event version after the
 * command; `GET /api/v1/accounts/{id}` shows the change once its own `ETag` has reached that version.
 */
@RestController
@RequestMapping("/api/v1/accounts")
class AccountCommandController(private val commandDispatcher: CommandDispatcher) {
    /**
     * Opens an account with a server-generated id and returns `201 Created` with its location.
     *
     * A replayed idempotency key returns the account opened by the original request, not a new one.
     */
    @PostMapping
    fun openAccount(
        @Valid @RequestBody request: OpenAccountRequest,
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<CommandResponse> {
        val command = OpenAccountCommand(
            accountId = UUID.randomUUID().toString(),
            accountHolder = request.accountHolder,
            accountType = request.accountType,
            openingBalance = request.openingBalance,
            idempotencyKey = idempotencyKey,
        )
        val response = send(command, "Bank account opened successfully.")
        val location = ServletUriComponentsBuilder.fromCurrentRequestUri()
            .path("/{id}")
            .buildAndExpand(response.accountId)
            .toUri()
        return ResponseEntity.created(location).eTagFor(response).body(response)
    }

    /** Deposits funds into account [id]. */
    @PostMapping("/{id}/deposits")
    fun deposit(
        @PathVariable id: String,
        @Valid @RequestBody request: FundsRequest,
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<CommandResponse> =
        ok(send(DepositFundsCommand(id, request.amount, idempotencyKey), "Funds deposited successfully."))

    /** Withdraws funds from account [id]; the account cannot be overdrawn. */
    @PostMapping("/{id}/withdrawals")
    fun withdraw(
        @PathVariable id: String,
        @Valid @RequestBody request: FundsRequest,
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<CommandResponse> =
        ok(send(WithdrawFundsCommand(id, request.amount, idempotencyKey), "Funds withdrawn successfully."))

    /**
     * Closes account [id], which must have a zero balance.
     *
     * Closing is a state change, not a deletion: the account remains readable with `closed = true`.
     */
    @PostMapping("/{id}/closure")
    fun closeAccount(
        @PathVariable id: String,
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<CommandResponse> =
        ok(send(CloseAccountCommand(id, idempotencyKey), "Bank account closed successfully."))

    /** Dispatches [command] and builds the response body from its result. */
    private fun send(command: AccountCommand, message: String): CommandResponse {
        val result = commandDispatcher.send(command)
        return CommandResponse(result.aggregateId, result.version, message)
    }

    private fun ok(response: CommandResponse) = ResponseEntity.ok().eTagFor(response).body(response)

    /** Sets the `ETag` to the account's event version, when the result carries one. */
    private fun ResponseEntity.BodyBuilder.eTagFor(response: CommandResponse) =
        apply { response.version?.let { eTag(it.toString()) } }

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
