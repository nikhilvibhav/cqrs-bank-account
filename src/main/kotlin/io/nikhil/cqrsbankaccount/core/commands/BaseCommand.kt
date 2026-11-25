package io.nikhil.cqrsbankaccount.core.commands

/**
 * A request to change the state of an aggregate, sent through a
 * [io.nikhil.cqrsbankaccount.core.infra.CommandDispatcher].
 */
abstract class BaseCommand {
    /** Client-supplied key that makes retries of the same command safe; null means every attempt is new. */
    abstract val idempotencyKey: String?
}
