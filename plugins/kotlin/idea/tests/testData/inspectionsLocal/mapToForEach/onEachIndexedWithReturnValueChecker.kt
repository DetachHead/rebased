// FIX: Replace with 'forEachIndexed'
// PRIORITY: HIGH
// COMPILER_ARGUMENTS: -Xreturn-value-checker=check

fun test(list: List<String>) {
    list.on<caret>EachIndexed { index, string ->
        println("$index: $string")
    }
}
