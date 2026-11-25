package io.nikhil.cqrsbankaccount

import io.flamingock.api.annotations.EnableFlamingock
import io.flamingock.api.annotations.Stage
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@EnableFlamingock(stages = [Stage(location = "io.nikhil.cqrsbankaccount.migrations")])
@SpringBootApplication
class CqrsBankAccountApplication

fun main(args: Array<String>) {
    runApplication<CqrsBankAccountApplication>(*args)
}
