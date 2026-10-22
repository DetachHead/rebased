// COMPILER_ARGUMENTS: -Xcontext-sensitive-resolution
// PROBLEM: Qualifier can be removed via context-sensitive resolution
// FIX: Remove redundant qualifier name
package test

enum class MyEnum { A, B }

fun test() {
    val e: MyEnum = <caret>MyEnum.A
}
