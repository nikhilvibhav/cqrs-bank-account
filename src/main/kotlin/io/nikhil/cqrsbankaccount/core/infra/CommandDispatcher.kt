package io.nikhil.cqrsbankaccount.core.infra

import io.nikhil.cqrsbankaccount.core.commands.BaseCommand
import io.nikhil.cqrsbankaccount.core.commands.CommandResult

/**
 * Mediator between the API and the command handlers: callers send a command without knowing which
 * handler applies it.
 */
interface CommandDispatcher {
    /**
     * Sends [command] to its handler and waits for the result.
     *
     * @throws io.nikhil.cqrsbankaccount.core.exceptions.DomainException when the handler rejects the command.
     */
    fun send(command: BaseCommand): CommandResult
}
