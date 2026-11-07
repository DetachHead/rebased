// FIX: Replace with 'forEach'
// PRIORITY: HIGH
// COMPILER_ARGUMENTS: -Xreturn-value-checker=check

fun test(list: List<String>) {
    list.on<caret>Each { string ->
        if (string.isEmpty()) return@onEach
        println(string)
    }
}
