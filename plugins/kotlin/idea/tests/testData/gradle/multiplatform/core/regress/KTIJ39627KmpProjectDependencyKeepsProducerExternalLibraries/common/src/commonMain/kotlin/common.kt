//region Test configuration
// - hidden: line markers
//endregion
package producer

import kotlinx.serialization.json.Json

fun normalizeJson(text: String): String = Json.parseToJsonElement(text).toString()