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

@RestController
@RequestMapping("/api/v1/accounts")
class AccountCommandController(private val commandDispatcher: CommandDispatcher) {
    @PostMapping
    fun openAccount(
        @Valid @RequestBody request: OpenAccountRequest,
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<CommandResponse> {
        // A replayed idempotency key returns the account opened by the original request, not this id.
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

    @PostMapping("/{id}/deposits")
    fun deposit(
        @PathVariable id: String,
        @Valid @RequestBody request: FundsRequest,
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<CommandResponse> =
        ok(send(DepositFundsCommand(id, request.amount, idempotencyKey), "Funds deposited successfully."))

    @PostMapping("/{id}/withdrawals")
    fun withdraw(
        @PathVariable id: String,
        @Valid @RequestBody request: FundsRequest,
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<CommandResponse> =
        ok(send(WithdrawFundsCommand(id, request.amount, idempotencyKey), "Funds withdrawn successfully."))

    // Closing is a state change, not a deletion: the account remains readable with closed = true.
    @PostMapping("/{id}/closure")
    fun closeAccount(
        @PathVariable id: String,
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<CommandResponse> =
        ok(send(CloseAccountCommand(id, idempotencyKey), "Bank account closed successfully."))

    private fun send(command: AccountCommand, message: String): CommandResponse {
        val result = commandDispatcher.send(command)
        return CommandResponse(result.aggregateId, result.version, message)
    }

    private fun ok(response: CommandResponse) = ResponseEntity.ok().eTagFor(response).body(response)

    private fun ResponseEntity.BodyBuilder.eTagFor(response: CommandResponse) =
        apply { response.version?.let { eTag(it.toString()) } }

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
