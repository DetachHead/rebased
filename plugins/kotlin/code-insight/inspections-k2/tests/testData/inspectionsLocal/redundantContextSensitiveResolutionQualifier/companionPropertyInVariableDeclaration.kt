// COMPILER_ARGUMENTS: -Xcontext-sensitive-resolution
// PROBLEM: Qualifier can be removed via context-sensitive resolution
// FIX: Remove redundant qualifier name
package test

class MyColor(val rgb: Int) {
    companion object {
        val RED: MyColor = MyColor(0xFF0000) // type is explicit here
    }
}

fun test() {
    val c: MyColor = <caret>MyColor.RED
}
