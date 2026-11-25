package io.nikhil.cqrsbankaccount.core.infra

import io.nikhil.cqrsbankaccount.core.commands.BaseCommand
import io.nikhil.cqrsbankaccount.core.commands.CommandResult
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.integration.core.MessagingTemplate
import org.springframework.integration.support.MessageBuilder
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessagingException
import org.springframework.stereotype.Component

@Component
class SpringIntegrationCommandDispatcher(
    @Qualifier("accountCommandChannel") private val commandChannel: MessageChannel,
) : CommandDispatcher {
    private val messagingTemplate = MessagingTemplate()

    // Request/reply: the handler's return value comes back as the reply message, so the command itself
    // never has to be mutated to carry a result, whichever channel type is used.
    override fun send(command: BaseCommand): CommandResult {
        val reply = try {
            messagingTemplate.sendAndReceive(commandChannel, MessageBuilder.withPayload(command).build())
        } catch (exception: MessagingException) {
            // Callers should see the handler's own exception, not the messaging wrapper around it.
            throw exception.cause as? RuntimeException ?: exception
        }
        checkNotNull(reply) { "The command handler did not reply." }
        return reply.payload as CommandResult
    }
}
