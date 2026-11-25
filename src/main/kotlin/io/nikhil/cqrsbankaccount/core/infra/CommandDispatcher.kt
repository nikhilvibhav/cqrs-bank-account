package io.nikhil.cqrsbankaccount.core.infra

import io.nikhil.cqrsbankaccount.core.commands.BaseCommand
import io.nikhil.cqrsbankaccount.core.commands.CommandResult

// Mediator
interface CommandDispatcher {
    fun send(command: BaseCommand): CommandResult
}
