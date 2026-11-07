// "Convert to positional destructuring syntax with square brackets" "true"
// COMPILER_ARGUMENTS: -Xname-based-destructuring=complete
// WITH_STDLIB
// K2_ERROR: UNRESOLVED_REFERENCE
// K2_ERROR: UNRESOLVED_REFERENCE

fun test() {
    listOf("a" to 1).forEach { (<caret>str, num) -> }
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.codeinsights.impl.base.quickFix.ConvertToPositionalDestructuringFix
