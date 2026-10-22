// "Convert to positional destructuring syntax with square brackets" "true"
// COMPILER_ARGUMENTS: -Xname-based-destructuring=complete
// WITH_STDLIB
// K2_ERROR: NAME_BASED_DESTRUCTURING_UNDERSCORE_WITHOUT_RENAMING
// K2_ERROR: UNRESOLVED_REFERENCE

fun test() {
    listOf("a" to 1).forEach { (<caret>str, _) -> }
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.codeinsights.impl.base.quickFix.ConvertToPositionalDestructuringFix