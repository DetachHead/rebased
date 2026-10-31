// COMPILER_ARGUMENTS: -Xcontext-sensitive-resolution
// PROBLEM: Qualifier can be removed via context-sensitive resolution
// FIX: Remove redundant qualifier name
package test

sealed class MyResult {
    class Ok(val value: String) : MyResult()
    class Err(val message: String) : MyResult()
}

fun handle(r: MyResult) {
    if (r is <caret>MyResult.Ok) {}
}
