package io.nikhil.cqrsbankaccount.core.commands

abstract class BaseCommand {
    // Client-supplied key that makes retries of the same command safe; null means every attempt is new.
    abstract val idempotencyKey: String?
}
