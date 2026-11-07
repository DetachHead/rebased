//region Test configuration
// - hidden: line markers
//endregion
package consumer

import producer.normalizeJson

fun greeting(): String = normalizeJson("\"hello\"")