package io.getaimly.backend.subscription

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "tribute")
class TributeProperties {
    var apiKey: String = ""
    var signatureStrict: Boolean = true
    var maxEventAgeMinutes: Long = 120
}