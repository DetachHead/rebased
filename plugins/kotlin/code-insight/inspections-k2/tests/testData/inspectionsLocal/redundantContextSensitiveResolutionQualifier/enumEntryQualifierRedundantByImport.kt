// COMPILER_ARGUMENTS: -Xcontext-sensitive-resolution
// PROBLEM: none
package test

import test.MyEnum.A

enum class MyEnum { A, B }

fun test() {
    val e: MyEnum = <caret>MyEnum.A
}
