// COMPILER_ARGUMENTS: -Xcontext-sensitive-resolution
// PROBLEM: none
package test

enum class MyEnum { A, B }

fun test() {
    val e = <caret>MyEnum.A
}
