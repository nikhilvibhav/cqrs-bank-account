package io.nikhil.cqrsbankaccount.common.dtos

import io.nikhil.cqrsbankaccount.core.exceptions.AccountClosedException
import io.nikhil.cqrsbankaccount.core.exceptions.AccountNotFoundException
import io.nikhil.cqrsbankaccount.core.exceptions.ConcurrencyConflictException
import io.nikhil.cqrsbankaccount.core.exceptions.DomainException
import io.nikhil.cqrsbankaccount.core.exceptions.IdempotencyKeyReusedException
import io.nikhil.cqrsbankaccount.core.exceptions.InsufficientFundsException
import io.nikhil.cqrsbankaccount.core.exceptions.InvalidCommandException
import io.nikhil.cqrsbankaccount.core.exceptions.NonZeroBalanceException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Turns exceptions raised while handling a request into RFC 9457 problem details.
 *
 * Extending [ResponseEntityExceptionHandler] makes Spring MVC's own errors (unreadable body, missing header,
 * type mismatch) use the same problem format as domain errors.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    /**
     * Maps each [DomainException] to its HTTP status and a short title, keeping the exception's message as
     * the problem's detail.
     */
    @ExceptionHandler(DomainException::class)
    fun domainException(exception: DomainException): ProblemDetail {
        val (status, title) = when (exception) {
            is InvalidCommandException -> HttpStatus.BAD_REQUEST to "Invalid command"
            is AccountNotFoundException -> HttpStatus.NOT_FOUND to "Account not found"
            is AccountClosedException -> HttpStatus.UNPROCESSABLE_CONTENT to "Account closed"
            is InsufficientFundsException -> HttpStatus.UNPROCESSABLE_CONTENT to "Insufficient funds"
            is NonZeroBalanceException -> HttpStatus.UNPROCESSABLE_CONTENT to "Non-zero balance"
            is IdempotencyKeyReusedException -> HttpStatus.UNPROCESSABLE_CONTENT to "Idempotency key reused"
            is ConcurrencyConflictException -> HttpStatus.CONFLICT to "Concurrent modification"
        }
        return ProblemDetail.forStatusAndDetail(status, exception.message).apply { this.title = title }
    }

    /**
     * Handles an invalid request body by listing each failed field in an `errors` property, rather than
     * only Spring's generic "Invalid request content."
     */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        ex.body.setProperty("errors", ex.bindingResult.fieldErrors.map { fieldError(it.field, it.defaultMessage) })
        return super.handleMethodArgumentNotValid(ex, headers, status, request)
    }

    /**
     * Handles an invalid query parameter, such as a page size above the maximum, by listing each failed
     * parameter in an `errors` property.
     */
    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors = ex.parameterValidationResults.flatMap { result ->
            result.resolvableErrors.map { fieldError(result.methodParameter.parameterName, it.defaultMessage) }
        }
        ex.body.setProperty("errors", errors)
        return super.handleHandlerMethodValidationException(ex, headers, status, request)
    }

    /** One entry of a problem's `errors` property. */
    private fun fieldError(field: String?, message: String?) = mapOf("field" to field, "message" to message)

    /**
     * Logs any other exception and returns a 500 without its message, since it is a defect whose details
     * must not reach the client.
     */
    @ExceptionHandler(Exception::class)
    fun unexpectedException(exception: Exception): ProblemDetail {
        log.error("Unhandled exception while processing a request.", exception)
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.")
    }
}
